package com.caopan.platform.geo.report;

import com.caopan.platform.api.service.GeoService;
import com.caopan.platform.api.vo.RegionVO;
import com.caopan.platform.api.vo.ReverseGeocodeVO;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.util.LangUtil;
import com.caopan.platform.geo.cache.GeoCacheKeys;
import com.caopan.platform.geo.cache.TieredCache;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.caopan.platform.geo.service.support.GeoDataCache;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 经纬度反查：本库近邻（短缓存 + 可选 SPATIAL）优先，未覆盖时 Nominatim 名匹配兜底。
 * <p>对外返回 Nominatim 风格精简结构。</p>
 */
@Service
public class ReverseGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(ReverseGeocodeService.class);
    private static final String NOTE_LOCAL =
            "结果来自本库 geo_region 坐标近邻；仅当本库无坐标覆盖时才会回退地图供应商。";
    private static final String NOTE_OSM =
            "本库坐标未命中，已回退地图供应商（OpenStreetMap 等）名匹配；供应商数据可能不准确。";

    private static final NearestStage STREET_STAGE = new NearestStage("STREET", 4, 5, 1.0);
    private static final double COARSE_QUERY_RADIUS_KM = 50.0;
    private static final double MAX_DISTRICT_KM = 5.0;
    private static final double MAX_STATE_KM = 50.0;
    private static final double MAX_COUNTRY_KM = 500.0;

    private static final int BBOX_CANDIDATE_LIMIT = 32;
    private static final TypeReference<ReverseGeocodeVO> VO_TYPE = new TypeReference<>() {};

    private final GeocodeClient geocodeClient;
    private final GeoDataCache geoDataCache;
    private final GeoRegionMapper geoRegionMapper;
    private final GeoService geoService;
    private final TieredCache tieredCache;
    private final AtomicBoolean spatialReady = new AtomicBoolean(false);

    public ReverseGeocodeService(
            GeocodeClient geocodeClient,
            GeoDataCache geoDataCache,
            GeoRegionMapper geoRegionMapper,
            GeoService geoService,
            TieredCache tieredCache) {
        this.geocodeClient = geocodeClient;
        this.geoDataCache = geoDataCache;
        this.geoRegionMapper = geoRegionMapper;
        this.geoService = geoService;
        this.tieredCache = tieredCache;
    }

    @PostConstruct
    void detectSpatialColumn() {
        try {
            geoRegionMapper.listNearestSpatial(
                    0, 0, envelopeWkt(0, 0, 0.01), 1000, "ZZ", 5, 5, 1);
            spatialReady.set(true);
            log.info("reverse geocode spatial index ready (geo_region_point)");
        } catch (Exception e) {
            spatialReady.set(false);
            log.info("reverse geocode spatial unavailable, fallback bbox index: {}", e.toString());
        }
    }

    public ReverseGeocodeVO reverse(Double lat, Double lon, String lang) {
        return reverse(lat, lon, lang, null);
    }

    public ReverseGeocodeVO reverse(Double lat, Double lon, String lang, String countryCode) {
        validateLatLon(lat, lon);
        String code = normalizeCountryCode(countryCode);
        String langKey = lang == null ? "" : lang.trim();
        long[] grid = GeoCacheKeys.reverseGrid(lat, lon);
        String cacheKey = GeoCacheKeys.reverse(grid[0], grid[1], code, langKey);
        ReverseGeocodeVO cached = tieredCache.get(
                cacheKey, VO_TYPE, GeoCacheKeys.L2_REVERSE_TTL, () -> loadReverse(lat, lon, langKey, code));
        return cached != null ? cached : emptyResult(lat, lon);
    }

    private ReverseGeocodeVO loadReverse(double lat, double lon, String lang, String countryCode) {
        MatchWork dbMatch = matchFromDb(lat, lon, lang, countryCode);
        if (dbMatch.regionId != null) {
            return toSimpleVo(lat, lon, "local", NOTE_LOCAL, dbMatch, null);
        }

        Optional<GeocodeClient.ReverseResult> rawOpt = geocodeClient.reverse(lat, lon, null);
        if (rawOpt.isEmpty()) {
            ReverseGeocodeVO empty = emptyResult(lat, lon);
            empty.setLicence(NOTE_OSM);
            empty.setClazz(geocodeClient.currentProvider());
            return empty;
        }
        GeocodeClient.ReverseResult raw = rawOpt.get();
        MatchWork osmMatch = matchStreetUpwardByName(raw, lang, countryCode);
        return toSimpleVo(lat, lon, geocodeClient.currentProvider(), NOTE_OSM, osmMatch, raw);
    }

    private ReverseGeocodeVO toSimpleVo(
            double lat, double lon, String clazz, String licence,
            MatchWork match, GeocodeClient.ReverseResult raw) {
        ReverseGeocodeVO vo = new ReverseGeocodeVO();
        vo.setLat(formatCoord(lat));
        vo.setLon(formatCoord(lon));
        vo.setLicence(licence);
        vo.setClazz(clazz);
        vo.setOsm(null);

        if (match != null && match.regionId != null) {
            vo.setId(match.regionId);
            vo.setDisplayName(match.fullPathName);
            RegionVO leaf = match.path == null || match.path.isEmpty()
                    ? null : match.path.get(match.path.size() - 1);
            vo.setName(leaf != null ? leaf.getDisplayName() : match.fullPathName);
            vo.setAddresstype(leaf != null && StringUtils.hasText(leaf.getRegionType())
                    ? leaf.getRegionType() : levelAddressType(match.matchedLevel));
            vo.setPlaceRank(placeRankOf(match.matchedLevel, vo.getAddresstype()));
            vo.setAddress(addressFromPath(match.path));
            if (raw != null && raw.addressParts() != null) {
                String postcode = raw.addressParts().get("postcode");
                if (StringUtils.hasText(postcode) && !vo.getAddress().containsKey("postcode")) {
                    vo.getAddress().put("postcode", postcode);
                }
            }
        } else if (raw != null) {
            vo.setId(raw.placeId());
            vo.setDisplayName(raw.displayName());
            vo.setName(StringUtils.hasText(raw.name()) ? raw.name() : raw.displayName());
            vo.setAddresstype(StringUtils.hasText(raw.addressType()) ? raw.addressType() : raw.type());
            vo.setPlaceRank(raw.placeRank());
            vo.setImportance(raw.importance());
            vo.setAddress(raw.addressParts() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(raw.addressParts()));
        }

        if (raw != null && raw.boundingBox() != null && !raw.boundingBox().isEmpty()) {
            vo.setBoundingbox(new ArrayList<>(raw.boundingBox()));
        } else {
            vo.setBoundingbox(List.of());
        }
        if (vo.getImportance() == null && raw != null) {
            vo.setImportance(raw.importance());
        }
        return vo;
    }

    private MatchWork matchFromDb(double lat, double lon, String lang, String countryCode) {
        MatchWork match = new MatchWork("DB_NEAREST");

        NearestHit street = queryNearest(lat, lon, countryCode,
                STREET_STAGE.minLevel, STREET_STAGE.maxLevel, STREET_STAGE.maxDistanceKm);
        match.trials.add(toTrial(STREET_STAGE.name, STREET_STAGE.minLevel, STREET_STAGE.maxLevel,
                STREET_STAGE.maxDistanceKm, street, lang));
        if (street != null) {
            applyHit(match, street.region(), lang, street.distanceKm());
            return match;
        }

        List<GeoRegion> coarseList = loadCandidates(lat, lon, countryCode, 1, 3, COARSE_QUERY_RADIUS_KM);
        NearestHit picked = pickNearestWithLevelCaps(coarseList, lat, lon);
        MatchTrial t = new MatchTrial();
        t.level = 1;
        t.osmKey = "COARSE_L1_L3";
        t.query = String.format(Locale.ROOT, "r<=%.1fkm L1-3 merged", COARSE_QUERY_RADIUS_KM);
        if (picked == null) {
            t.hit = false;
            t.reason = coarseList == null || coarseList.isEmpty() ? "NO_CANDIDATE" : "NO_HIT";
            match.trials.add(t);
            return match;
        }
        t.hit = true;
        t.regionId = picked.region().getId();
        t.regionName = LangUtil.resolveDisplayName(
                lang, picked.region().getName(), picked.region().getNameEn(), picked.region().getNameCh());
        t.distanceKm = roundKm(picked.distanceKm());
        t.reason = "NEAREST";
        match.trials.add(t);
        applyHit(match, picked.region(), lang, picked.distanceKm());
        return match;
    }

    private NearestHit queryNearest(
            double lat, double lon, String countryCode, int minLevel, int maxLevel, double maxKm) {
        List<GeoRegion> list = loadCandidates(lat, lon, countryCode, minLevel, maxLevel, maxKm);
        return pickNearest(list, lat, lon, maxKm);
    }

    private List<GeoRegion> loadCandidates(
            double lat, double lon, String countryCode, int minLevel, int maxLevel, double maxKm) {
        if (spatialReady.get()) {
            try {
                return geoRegionMapper.listNearestSpatial(
                        lat, lon, envelopeWkt(lat, lon, maxKm), maxKm * 1000.0,
                        countryCode, minLevel, maxLevel, BBOX_CANDIDATE_LIMIT);
            } catch (Exception e) {
                log.warn("spatial nearest failed, fallback bbox: {}", e.toString());
                spatialReady.set(false);
            }
        }
        BBox box = bbox(lat, lon, maxKm);
        return geoRegionMapper.listNearestInBoundingBox(
                lat, lon, box.minLat(), box.maxLat(), box.minLon(), box.maxLon(),
                countryCode, minLevel, maxLevel, BBOX_CANDIDATE_LIMIT);
    }

    private MatchTrial toTrial(
            String name, int minLevel, int maxLevel, double maxKm, NearestHit hit, String lang) {
        MatchTrial t = new MatchTrial();
        t.level = minLevel;
        t.osmKey = name;
        t.query = String.format(Locale.ROOT, "r<=%.1fkm L%d-%d", maxKm, minLevel, maxLevel);
        if (hit == null) {
            t.hit = false;
            t.reason = "NO_HIT";
            return t;
        }
        t.hit = true;
        t.regionId = hit.region().getId();
        t.regionName = LangUtil.resolveDisplayName(
                lang, hit.region().getName(), hit.region().getNameEn(), hit.region().getNameCh());
        t.distanceKm = roundKm(hit.distanceKm());
        t.reason = "NEAREST";
        return t;
    }

    private MatchWork matchStreetUpwardByName(
            GeocodeClient.ReverseResult raw, String lang, String preferCountry) {
        MatchWork match = new MatchWork("OSM_STREET_UPWARD");
        Map<String, String> address = raw.addressParts() == null
                ? Map.of() : raw.addressParts();
        String countryCode = StringUtils.hasText(preferCountry)
                ? preferCountry
                : StreetUpwardMatcher.extractCountryCode(address);
        if (!StringUtils.hasText(countryCode)) {
            match.trials.add(trial(1, "country_code", null, null, false, null, null, null, "NO_CANDIDATE"));
            return match;
        }

        for (StreetUpwardMatcher.MatchStage stage : StreetUpwardMatcher.stagesStreetUpward()) {
            List<Map.Entry<String, String>> candidates =
                    StreetUpwardMatcher.candidatesForStage(address, stage);
            if (candidates.isEmpty()) {
                match.trials.add(trial(stage.tryLevels()[0], stage.name(), null, null,
                        false, null, null, null, "NO_CANDIDATE"));
                continue;
            }
            StreetUpwardMatcher.ScoredHit best = null;
            for (Map.Entry<String, String> cand : candidates) {
                for (String query : StreetUpwardMatcher.searchQueries(cand.getValue())) {
                    for (int level : stage.tryLevels()) {
                        List<GeoRegion> hits = geoDataCache.search(query, countryCode, level, 20);
                        StreetUpwardMatcher.ScoredHit scored =
                                StreetUpwardMatcher.pickBest(hits, query, raw.lat(), raw.lon());
                        match.trials.add(trial(
                                level, cand.getKey(), cand.getValue(), query,
                                scored != null, scored == null ? null : scored.region().getId(),
                                scored == null ? null : LangUtil.resolveDisplayName(
                                        lang, scored.region().getName(), scored.region().getNameEn(),
                                        scored.region().getNameCh()),
                                scored == null ? null : roundKm(scored.distanceKm()),
                                scored == null ? "NO_HIT" : scored.reason()));
                        if (scored != null && (best == null || scored.rank() > best.rank()
                                || (scored.rank() == best.rank() && scored.distanceKm() < best.distanceKm()))) {
                            best = scored;
                        }
                    }
                }
            }
            if (best != null) {
                applyHit(match, best.region(), lang,
                        best.distanceKm() < Double.MAX_VALUE ? best.distanceKm() : null);
                return match;
            }
        }

        GeoRegion country = geoRegionMapper.findCountryByCode(countryCode);
        MatchTrial t = trial(1, "country_code", countryCode, countryCode,
                country != null, country == null ? null : country.getId(),
                country == null ? null : LangUtil.resolveDisplayName(
                        lang, country.getName(), country.getNameEn(), country.getNameCh()),
                null, country == null ? "NO_HIT" : "COUNTRY_CODE");
        match.trials.add(t);
        if (country != null) {
            applyHit(match, country, lang, null);
        }
        return match;
    }

    static NearestHit pickNearest(List<GeoRegion> candidates, double lat, double lon, double maxKm) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        NearestHit best = null;
        for (GeoRegion r : candidates) {
            if (r.getLatitude() == null || r.getLongitude() == null || r.getLevel() == null) {
                continue;
            }
            double d = GeoDistanceUtil.distanceKm(
                    lat, lon, r.getLatitude().doubleValue(), r.getLongitude().doubleValue());
            if (d > maxKm) {
                continue;
            }
            if (best == null) {
                best = new NearestHit(r, d);
                continue;
            }
            int deeper = Integer.compare(r.getLevel(), best.region().getLevel());
            if (deeper > 0 || (deeper == 0 && d < best.distanceKm())) {
                best = new NearestHit(r, d);
            }
        }
        return best;
    }

    static NearestHit pickNearestWithLevelCaps(List<GeoRegion> candidates, double lat, double lon) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        NearestHit best = null;
        for (GeoRegion r : candidates) {
            if (r.getLatitude() == null || r.getLongitude() == null || r.getLevel() == null) {
                continue;
            }
            double d = GeoDistanceUtil.distanceKm(
                    lat, lon, r.getLatitude().doubleValue(), r.getLongitude().doubleValue());
            double cap = levelCapKm(r.getLevel());
            if (cap < 0 || d > cap) {
                continue;
            }
            if (best == null) {
                best = new NearestHit(r, d);
                continue;
            }
            int deeper = Integer.compare(r.getLevel(), best.region().getLevel());
            if (deeper > 0 || (deeper == 0 && d < best.distanceKm())) {
                best = new NearestHit(r, d);
            }
        }
        return best;
    }

    private static double levelCapKm(int level) {
        return switch (level) {
            case 3 -> MAX_DISTRICT_KM;
            case 2 -> MAX_STATE_KM;
            case 1 -> MAX_COUNTRY_KM;
            default -> -1;
        };
    }

    private void applyHit(MatchWork match, GeoRegion region, String lang, Double distanceKm) {
        match.matchedLevel = region.getLevel();
        match.regionId = region.getId();
        match.distanceKm = distanceKm == null ? null : roundKm(distanceKm);
        List<RegionVO> path = geoService.getPath(region.getId(), lang);
        match.path = path;
        match.fullPathName = path.stream()
                .map(RegionVO::getDisplayName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("/"));
    }

    static Map<String, String> addressFromPath(List<RegionVO> path) {
        Map<String, String> addr = new LinkedHashMap<>();
        if (path == null || path.isEmpty()) {
            return addr;
        }
        String countryCode = null;
        String stateCode = null;
        for (RegionVO r : path) {
            int level = r.getLevel() == null ? 0 : r.getLevel();
            String type = r.getRegionType() == null ? "" : r.getRegionType().trim().toUpperCase(Locale.ROOT);
            String name = StringUtils.hasText(r.getDisplayName()) ? r.getDisplayName() : r.getName();
            String code = r.getCode() == null ? "" : r.getCode();
            switch (level) {
                case 1 -> {
                    putIfText(addr, "country", name);
                    countryCode = r.getCountryCode();
                    if (StringUtils.hasText(countryCode)) {
                        addr.put("country_code", countryCode.toLowerCase(Locale.ROOT));
                    }
                }
                case 2 -> {
                    putIfText(addr, "state", name);
                    addr.put("state_code", code);
                    putIfText(addr, "state_type", type);
                    stateCode = code;
                }
                case 3 -> {
                    if (isCityType(type)) {
                        putIfText(addr, "city", name);
                        addr.put("city_code", code);
                        putIfText(addr, "city_type", type);
                    } else {
                        putIfText(addr, "county", name);
                        addr.put("county_code", code);
                        putIfText(addr, "county_type", type);
                    }
                }
                case 4 -> {
                    if (isStreetType(type)) {
                        putRoad(addr, name, code, type);
                    } else if (!addr.containsKey("county")) {
                        putIfText(addr, "county", name);
                        addr.put("county_code", code);
                        putIfText(addr, "county_type", type);
                    } else {
                        putIfText(addr, "city", name);
                        addr.put("city_code", code);
                        putIfText(addr, "city_type", type);
                    }
                }
                case 5 -> putRoad(addr, name, code, type);
                default -> {
                }
            }
        }
        if (StringUtils.hasText(countryCode) && StringUtils.hasText(stateCode)) {
            addr.put("ISO3166-2-lvl4", countryCode.toUpperCase(Locale.ROOT) + "-" + stateCode);
        }
        return addr;
    }

    private static void putRoad(Map<String, String> addr, String name, String code, String type) {
        putIfText(addr, "road", name);
        addr.put("road_code", code == null ? "" : code);
        addr.put("road_type", type == null ? "" : type);
    }

    private static void putIfText(Map<String, String> addr, String key, String value) {
        if (StringUtils.hasText(value)) {
            addr.put(key, value);
        }
    }

    private static boolean isStreetType(String type) {
        return type.contains("STREET") || type.contains("ROAD") || "TOWN".equals(type);
    }

    private static boolean isCityType(String type) {
        return type.contains("CITY") || type.contains("MUNICIPALITY");
    }

    private static String levelAddressType(Integer level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case 1 -> "COUNTRY";
            case 2 -> "PROVINCE";
            case 3 -> "CITY";
            case 4 -> "DISTRICT";
            case 5 -> "STREET";
            default -> "REGION";
        };
    }

    private static int placeRankOf(Integer level, String addressType) {
        if (addressType != null && isStreetType(addressType.toUpperCase(Locale.ROOT))) {
            return 26;
        }
        if (level == null) {
            return 16;
        }
        return switch (level) {
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 14;
            case 4 -> 18;
            case 5 -> 26;
            default -> 16;
        };
    }

    private static ReverseGeocodeVO emptyResult(double lat, double lon) {
        ReverseGeocodeVO vo = new ReverseGeocodeVO();
        vo.setLat(formatCoord(lat));
        vo.setLon(formatCoord(lon));
        vo.setLicence(NOTE_LOCAL);
        vo.setClazz("local");
        vo.setOsm(null);
        vo.setAddress(new LinkedHashMap<>());
        vo.setBoundingbox(List.of());
        return vo;
    }

    private static MatchTrial trial(
            Integer level, String osmKey, String osmValue, String query,
            boolean hit, Long regionId, String regionName, Double distanceKm, String reason) {
        MatchTrial t = new MatchTrial();
        t.level = level;
        t.osmKey = osmKey;
        t.osmValue = osmValue;
        t.query = query;
        t.hit = hit;
        t.regionId = regionId;
        t.regionName = regionName;
        t.distanceKm = distanceKm;
        t.reason = reason;
        return t;
    }

    static String envelopeWkt(double lat, double lon, double radiusKm) {
        BBox b = bbox(lat, lon, radiusKm);
        return String.format(Locale.ROOT,
                "POLYGON((%.8f %.8f, %.8f %.8f, %.8f %.8f, %.8f %.8f, %.8f %.8f))",
                b.minLat(), b.minLon(),
                b.minLat(), b.maxLon(),
                b.maxLat(), b.maxLon(),
                b.maxLat(), b.minLon(),
                b.minLat(), b.minLon());
    }

    static BBox bbox(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / 111.0;
        double cos = Math.cos(Math.toRadians(lat));
        double dLon = radiusKm / (111.0 * Math.max(0.2, Math.abs(cos)));
        return new BBox(
                clamp(lat - dLat, -90, 90),
                clamp(lat + dLat, -90, 90),
                clamp(lon - dLon, -180, 180),
                clamp(lon + dLon, -180, 180));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Double roundKm(double km) {
        if (km >= Double.MAX_VALUE / 2) {
            return null;
        }
        return Math.round(km * 1000.0) / 1000.0;
    }

    private static String formatCoord(double v) {
        return String.format(Locale.ROOT, "%.7f", v);
    }

    static String toAcceptLanguage(String lang) {
        if (!StringUtils.hasText(lang)) {
            return null;
        }
        String n = lang.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "zh", "ch", "zh-cn", "zh_cn" -> "zh-CN";
            case "en", "en-us", "en_us" -> "en";
            case "local" -> null;
            default -> lang.trim();
        };
    }

    private static String normalizeCountryCode(String countryCode) {
        if (!StringUtils.hasText(countryCode)) {
            return null;
        }
        String c = countryCode.trim().toUpperCase(Locale.ROOT);
        if (c.length() != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return c;
    }

    private static void validateLatLon(Double lat, Double lon) {
        if (lat == null || lon == null
                || lat < -90 || lat > 90
                || lon < -180 || lon > 180
                || lat.isNaN() || lon.isNaN()
                || lat.isInfinite() || lon.isInfinite()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    record NearestStage(String name, int minLevel, int maxLevel, double maxDistanceKm) {
    }

    record BBox(double minLat, double maxLat, double minLon, double maxLon) {
    }

    record NearestHit(GeoRegion region, double distanceKm) {
    }

    /** 内部匹配工作集，不对外暴露。 */
    static final class MatchWork {
        final String strategy;
        Integer matchedLevel;
        Long regionId;
        String fullPathName;
        Double distanceKm;
        List<RegionVO> path = new ArrayList<>();
        final List<MatchTrial> trials = new ArrayList<>();

        MatchWork(String strategy) {
            this.strategy = strategy;
        }
    }

    static final class MatchTrial {
        Integer level;
        String osmKey;
        String osmValue;
        String query;
        Boolean hit;
        Long regionId;
        String regionName;
        Double distanceKm;
        String reason;
    }
}

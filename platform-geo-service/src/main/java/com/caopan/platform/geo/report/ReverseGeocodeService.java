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
 */
@Service
public class ReverseGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(ReverseGeocodeService.class);
    private static final String NOTE_LOCAL =
            "结果来自本库 geo_region 坐标近邻；仅当本库无坐标覆盖时才会回退地图供应商。";
    private static final String NOTE_OSM =
            "本库坐标未命中，已回退地图供应商（OpenStreetMap 等）名匹配；供应商数据可能不准确，以 match.path 为准。";

    private static final NearestStage STREET_STAGE = new NearestStage("STREET", 4, 5, 1.0);
    /** 街道未命中后，L1–L3 合并一次查询（半径取省州上限） */
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
        ReverseGeocodeVO vo = new ReverseGeocodeVO();
        vo.setLat(lat);
        vo.setLon(lon);

        ReverseGeocodeVO.MatchDetail dbMatch = matchFromDb(lat, lon, lang, countryCode);
        if (dbMatch.getMatchedRegionId() != null) {
            vo.setProvider("local");
            vo.setProviderStatus("OK");
            vo.setProviderNote(NOTE_LOCAL);
            vo.setDisplayName(dbMatch.getFullPathName());
            vo.setOsm(null);
            vo.setMatch(dbMatch);
            return vo;
        }

        vo.setProvider(geocodeClient.currentProvider());
        vo.setProviderNote(NOTE_OSM);
        Optional<GeocodeClient.ReverseResult> rawOpt = geocodeClient.reverse(lat, lon, null);
        if (rawOpt.isEmpty()) {
            vo.setProviderStatus("EMPTY");
            vo.setDisplayName(null);
            vo.setOsm(null);
            dbMatch.setStrategy("DB_NEAREST+OSM_FALLBACK");
            vo.setMatch(dbMatch);
            return vo;
        }
        GeocodeClient.ReverseResult raw = rawOpt.get();
        vo.setProviderStatus("OK");
        vo.setDisplayName(raw.displayName());
        vo.setOsm(toOsmDetail(raw));
        ReverseGeocodeVO.MatchDetail osmMatch = matchStreetUpwardByName(raw, lang, countryCode);
        List<ReverseGeocodeVO.MatchTrial> merged = new ArrayList<>(dbMatch.getTrials());
        merged.addAll(osmMatch.getTrials());
        osmMatch.setTrials(merged);
        osmMatch.setStrategy("OSM_STREET_UPWARD");
        vo.setMatch(osmMatch);
        return vo;
    }

    /**
     * 本库近邻：① 街道 1 次；② 未命中则 L1–L3 合并 1 次。
     */
    private ReverseGeocodeVO.MatchDetail matchFromDb(double lat, double lon, String lang, String countryCode) {
        ReverseGeocodeVO.MatchDetail match = emptyMatch("DB_NEAREST");

        NearestHit street = queryNearest(lat, lon, countryCode,
                STREET_STAGE.minLevel, STREET_STAGE.maxLevel, STREET_STAGE.maxDistanceKm);
        match.getTrials().add(toTrial(STREET_STAGE.name, STREET_STAGE.minLevel, STREET_STAGE.maxLevel,
                STREET_STAGE.maxDistanceKm, street, lang));
        if (street != null) {
            applyHit(match, street.region(), lang, street.distanceKm());
            return match;
        }

        // 粗层级合并：一次拉 L1–L3，再按层级距离上限择优
        List<GeoRegion> coarseList = loadCandidates(lat, lon, countryCode, 1, 3, COARSE_QUERY_RADIUS_KM);
        NearestHit picked = pickNearestWithLevelCaps(coarseList, lat, lon);
        ReverseGeocodeVO.MatchTrial t = new ReverseGeocodeVO.MatchTrial();
        t.setLevel(1);
        t.setOsmKey("COARSE_L1_L3");
        t.setQuery(String.format(Locale.ROOT, "r<=%.1fkm L1-3 merged", COARSE_QUERY_RADIUS_KM));
        if (picked == null) {
            t.setHit(false);
            t.setReason(coarseList == null || coarseList.isEmpty() ? "NO_CANDIDATE" : "NO_HIT");
            match.getTrials().add(t);
            return match;
        }
        t.setHit(true);
        t.setRegionId(picked.region().getId());
        t.setRegionName(LangUtil.resolveDisplayName(
                lang, picked.region().getName(), picked.region().getNameEn(), picked.region().getNameCh()));
        t.setDistanceKm(roundKm(picked.distanceKm()));
        t.setReason("NEAREST");
        match.getTrials().add(t);
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

    private ReverseGeocodeVO.MatchTrial toTrial(
            String name, int minLevel, int maxLevel, double maxKm, NearestHit hit, String lang) {
        ReverseGeocodeVO.MatchTrial t = new ReverseGeocodeVO.MatchTrial();
        t.setLevel(minLevel);
        t.setOsmKey(name);
        t.setQuery(String.format(Locale.ROOT, "r<=%.1fkm L%d-%d", maxKm, minLevel, maxLevel));
        if (hit == null) {
            t.setHit(false);
            t.setReason("NO_HIT");
            return t;
        }
        t.setHit(true);
        t.setRegionId(hit.region().getId());
        t.setRegionName(LangUtil.resolveDisplayName(
                lang, hit.region().getName(), hit.region().getNameEn(), hit.region().getNameCh()));
        t.setDistanceKm(roundKm(hit.distanceKm()));
        t.setReason("NEAREST");
        return t;
    }

    private ReverseGeocodeVO.MatchDetail matchStreetUpwardByName(
            GeocodeClient.ReverseResult raw, String lang, String preferCountry) {
        ReverseGeocodeVO.MatchDetail match = emptyMatch("OSM_STREET_UPWARD");
        Map<String, String> address = raw.addressParts() == null
                ? Map.of() : raw.addressParts();
        String countryCode = StringUtils.hasText(preferCountry)
                ? preferCountry
                : StreetUpwardMatcher.extractCountryCode(address);
        if (!StringUtils.hasText(countryCode)) {
            match.getTrials().add(trial(1, "country_code", null, null, false, null, null, null, "NO_CANDIDATE"));
            return match;
        }

        for (StreetUpwardMatcher.MatchStage stage : StreetUpwardMatcher.stagesStreetUpward()) {
            List<Map.Entry<String, String>> candidates =
                    StreetUpwardMatcher.candidatesForStage(address, stage);
            if (candidates.isEmpty()) {
                match.getTrials().add(trial(stage.tryLevels()[0], stage.name(), null, null,
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
                        match.getTrials().add(trial(
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
        ReverseGeocodeVO.MatchTrial t = trial(1, "country_code", countryCode, countryCode,
                country != null, country == null ? null : country.getId(),
                country == null ? null : LangUtil.resolveDisplayName(
                        lang, country.getName(), country.getNameEn(), country.getNameCh()),
                null, country == null ? "NO_HIT" : "COUNTRY_CODE");
        match.getTrials().add(t);
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

    /**
     * 粗层级择优：L3≤5km &gt; L2≤50km &gt; L1≤500km，同级取更近。
     */
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

    private void applyHit(ReverseGeocodeVO.MatchDetail match, GeoRegion region, String lang, Double distanceKm) {
        match.setMatchedLevel(region.getLevel());
        match.setMatchedRegionId(region.getId());
        match.setDistanceKm(distanceKm == null ? null : roundKm(distanceKm));
        List<RegionVO> path = geoService.getPath(region.getId(), lang);
        match.setPath(path);
        match.setFullPathName(path.stream()
                .map(RegionVO::getDisplayName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("/")));
    }

    private static ReverseGeocodeVO emptyResult(double lat, double lon) {
        ReverseGeocodeVO vo = new ReverseGeocodeVO();
        vo.setLat(lat);
        vo.setLon(lon);
        vo.setProvider("local");
        vo.setProviderStatus("EMPTY");
        vo.setProviderNote(NOTE_LOCAL);
        vo.setMatch(emptyMatch("DB_NEAREST"));
        return vo;
    }

    private static ReverseGeocodeVO.MatchDetail emptyMatch(String strategy) {
        ReverseGeocodeVO.MatchDetail m = new ReverseGeocodeVO.MatchDetail();
        m.setStrategy(strategy);
        m.setTrials(new ArrayList<>());
        m.setPath(new ArrayList<>());
        return m;
    }

    private static ReverseGeocodeVO.MatchTrial trial(
            Integer level, String osmKey, String osmValue, String query,
            boolean hit, Long regionId, String regionName, Double distanceKm, String reason) {
        ReverseGeocodeVO.MatchTrial t = new ReverseGeocodeVO.MatchTrial();
        t.setLevel(level);
        t.setOsmKey(osmKey);
        t.setOsmValue(osmValue);
        t.setQuery(query);
        t.setHit(hit);
        t.setRegionId(regionId);
        t.setRegionName(regionName);
        t.setDistanceKm(distanceKm);
        t.setReason(reason);
        return t;
    }

    private static ReverseGeocodeVO.OsmDetail toOsmDetail(GeocodeClient.ReverseResult raw) {
        ReverseGeocodeVO.OsmDetail osm = new ReverseGeocodeVO.OsmDetail();
        osm.setPlaceId(raw.placeId());
        osm.setOsmType(raw.osmType());
        osm.setOsmId(raw.osmId());
        osm.setCategory(raw.category());
        osm.setType(raw.type());
        osm.setAddressType(raw.addressType());
        osm.setName(raw.name());
        osm.setImportance(raw.importance());
        osm.setPlaceRank(raw.placeRank());
        osm.setBoundingBox(raw.boundingBox() == null ? List.of() : new ArrayList<>(raw.boundingBox()));
        Map<String, String> addr = raw.addressParts() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(raw.addressParts());
        osm.setAddress(addr);
        osm.setNormalized(StreetUpwardMatcher.normalize(addr));
        return osm;
    }

    /** POLYGON WKT，MySQL SRID 4326 轴序为 lat lon，闭合。 */
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
}

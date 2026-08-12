package com.caopan.platform.geo.report;

import com.caopan.platform.geo.entity.GeoRegion;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 街道级向上匹配：按 OSM 地址语义从细到粗，在本库候选层级中择优。
 * <p>各国 depth 不同（如 VN 街镇在 L4），故同一 OSM 字段会尝试多个 level。
 * OSM 分层/译名可能不准，匹配仅作参考。</p>
 */
public final class StreetUpwardMatcher {

    /** 匹配阶段：细地址 → 粗地址。 */
    public record MatchStage(String name, String[] osmKeys, int[] tryLevels) {
    }

    private static final List<MatchStage> STAGES = List.of(
            new MatchStage("STREET",
                    new String[]{"road", "pedestrian", "footway", "path", "cycleway", "residential", "street"},
                    new int[]{5, 4}),
            new MatchStage("DISTRICT",
                    new String[]{"suburb", "neighbourhood", "neighborhood", "city_district", "district",
                            "borough", "quarter", "county", "city_block"},
                    new int[]{4, 3}),
            new MatchStage("CITY",
                    new String[]{"city", "town", "municipality", "village", "hamlet"},
                    new int[]{3, 2}),
            new MatchStage("STATE",
                    new String[]{"state", "province", "region", "state_district"},
                    new int[]{2})
    );

    private StreetUpwardMatcher() {
    }

    public static List<MatchStage> stagesStreetUpward() {
        return STAGES;
    }

    /**
     * 从 address 中按优先级取出该阶段候选（key→value），去重保序。
     */
    public static List<Map.Entry<String, String>> candidatesForStage(Map<String, String> address, MatchStage stage) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (address == null || address.isEmpty() || stage == null) {
            return out;
        }
        for (String key : stage.osmKeys()) {
            String val = address.get(key);
            if (!StringUtils.hasText(val)) {
                continue;
            }
            String trimmed = val.trim();
            boolean dup = out.stream().anyMatch(e -> e.getValue().equalsIgnoreCase(trimmed));
            if (!dup) {
                out.add(Map.entry(key, trimmed));
            }
        }
        return out;
    }

    /** @deprecated 兼容旧测试；请用 {@link #candidatesForStage} */
    @Deprecated
    public static List<Map.Entry<String, String>> candidatesForLevel(Map<String, String> address, int level) {
        for (MatchStage stage : STAGES) {
            for (int lv : stage.tryLevels()) {
                if (lv == level) {
                    return candidatesForStage(address, stage);
                }
            }
        }
        return List.of();
    }

    /** @deprecated 兼容旧测试 */
    @Deprecated
    public static List<Integer> levelsStreetUpward() {
        return List.of(5, 4, 3, 2, 1);
    }

    /**
     * 生成搜索用 query：直接用 OSM 原名（本库 name/nameEn/nameCh 可命中则不必剥前缀）。
     */
    public static List<String> searchQueries(String osmValue) {
        List<String> queries = new ArrayList<>();
        if (!StringUtils.hasText(osmValue)) {
            return queries;
        }
        String raw = osmValue.trim();
        addQuery(queries, raw);
        if (raw.length() > 64) {
            addQuery(queries, raw.substring(0, 64).trim());
        }
        return queries;
    }

    private static void addQuery(List<String> queries, String q) {
        if (!StringUtils.hasText(q)) {
            return;
        }
        String t = q.trim();
        if (t.length() < 2 || t.indexOf('%') >= 0 || t.indexOf('_') >= 0) {
            return;
        }
        if (t.length() > 64) {
            t = t.substring(0, 64).trim();
        }
        String finalT = t;
        boolean dup = queries.stream().anyMatch(x -> x.equalsIgnoreCase(finalT));
        if (!dup) {
            queries.add(t);
        }
    }

    /**
     * 在候选区划中择优：精确 &gt; 包含 &gt; 前缀命中，同分再比坐标距离。
     */
    public static ScoredHit pickBest(List<GeoRegion> hits, String query, double lat, double lon) {
        if (hits == null || hits.isEmpty() || !StringUtils.hasText(query)) {
            return null;
        }
        ScoredHit best = null;
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (GeoRegion r : hits) {
            Score score = scoreRegion(r, q, lat, lon);
            if (score == null) {
                continue;
            }
            if (best == null || score.rank > best.rank
                    || (score.rank == best.rank && score.distanceKm < best.distanceKm)) {
                best = new ScoredHit(r, score.reason, score.rank, score.distanceKm);
            }
        }
        return best;
    }

    private static Score scoreRegion(GeoRegion r, String qLower, double lat, double lon) {
        String reason = null;
        int rank = 0;
        for (String name : namesOf(r)) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.equals(qLower)) {
                reason = "EXACT";
                rank = 300;
                break;
            }
            if (n.contains(qLower) || qLower.contains(n)) {
                if (rank < 200) {
                    reason = "CONTAINS";
                    rank = 200;
                }
            } else if (n.startsWith(qLower) || qLower.startsWith(n)) {
                if (rank < 100) {
                    reason = "PREFIX";
                    rank = 100;
                }
            }
        }
        if (rank == 0) {
            reason = "PREFIX";
            rank = 50;
        }
        double dist = distanceKm(r, lat, lon);
        return new Score(rank, dist, reason);
    }

    private static List<String> namesOf(GeoRegion r) {
        List<String> names = new ArrayList<>(3);
        if (StringUtils.hasText(r.getName())) {
            names.add(r.getName().trim());
        }
        if (StringUtils.hasText(r.getNameEn())) {
            names.add(r.getNameEn().trim());
        }
        if (StringUtils.hasText(r.getNameCh())) {
            names.add(r.getNameCh().trim());
        }
        return names;
    }

    public static double distanceKm(GeoRegion r, double lat, double lon) {
        if (r == null || r.getLatitude() == null || r.getLongitude() == null) {
            return Double.MAX_VALUE;
        }
        return haversineKm(lat, lon, r.getLatitude().doubleValue(), r.getLongitude().doubleValue());
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    public static String extractCountryCode(Map<String, String> address) {
        if (address == null) {
            return null;
        }
        String code = address.get("country_code");
        if (!StringUtils.hasText(code)) {
            code = address.get("countryCode");
        }
        if (!StringUtils.hasText(code) || code.trim().length() != 2) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    public static NormalizedAddress normalize(Map<String, String> address) {
        NormalizedAddress n = new NormalizedAddress();
        if (address == null) {
            return n;
        }
        n.setCountry(first(address, "country"));
        String cc = extractCountryCode(address);
        n.setCountryCode(cc == null ? null : cc.toLowerCase(Locale.ROOT));
        n.setState(first(address, "state", "province", "region"));
        n.setCity(first(address, "city", "town", "municipality", "village"));
        n.setDistrict(first(address, "city_district", "district", "borough"));
        n.setSuburb(first(address, "suburb", "neighbourhood", "neighborhood"));
        n.setCounty(first(address, "county"));
        n.setStreet(first(address, "road", "pedestrian", "street"));
        n.setHouseNumber(first(address, "house_number"));
        n.setPostcode(first(address, "postcode", "postal_code"));
        return n;
    }

    /** 归一化地址（内部使用，不对外暴露）。 */
    public static class NormalizedAddress {
        private String country;
        private String countryCode;
        private String state;
        private String city;
        private String district;
        private String suburb;
        private String county;
        private String street;
        private String houseNumber;
        private String postcode;

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getSuburb() { return suburb; }
        public void setSuburb(String suburb) { this.suburb = suburb; }
        public String getCounty() { return county; }
        public void setCounty(String county) { this.county = county; }
        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }
        public String getHouseNumber() { return houseNumber; }
        public void setHouseNumber(String houseNumber) { this.houseNumber = houseNumber; }
        public String getPostcode() { return postcode; }
        public void setPostcode(String postcode) { this.postcode = postcode; }
    }

    private static String first(Map<String, String> address, String... keys) {
        for (String k : keys) {
            String v = address.get(k);
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }

    public record ScoredHit(GeoRegion region, String reason, int rank, double distanceKm) {
        public ScoredHit {
            Objects.requireNonNull(region);
        }
    }

    private record Score(int rank, double distanceKm, String reason) {
    }
}

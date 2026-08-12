package com.caopan.platform.geo.report;

import com.caopan.platform.geo.entity.GeoRegion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreetUpwardMatcherTest {

    @Test
    void candidatesForStage_streetPrefersRoad() {
        Map<String, String> addr = new LinkedHashMap<>();
        addr.put("road", "Đường Nguyễn Như Trang");
        addr.put("county", "Phường Thống Nhất");
        addr.put("state", "Tỉnh Phú Thọ");
        addr.put("country_code", "vn");

        var street = StreetUpwardMatcher.stagesStreetUpward().get(0);
        List<Map.Entry<String, String>> l5 = StreetUpwardMatcher.candidatesForStage(addr, street);
        assertEquals(1, l5.size());
        assertEquals("road", l5.get(0).getKey());

        var district = StreetUpwardMatcher.stagesStreetUpward().get(1);
        List<Map.Entry<String, String>> l4 = StreetUpwardMatcher.candidatesForStage(addr, district);
        assertEquals("county", l4.get(0).getKey());
        assertEquals("Phường Thống Nhất", l4.get(0).getValue());
    }

    @Test
    void searchQueries_usesFullOsmName() {
        List<String> qs = StreetUpwardMatcher.searchQueries("Đường Nguyễn Như Trang");
        assertEquals(List.of("Đường Nguyễn Như Trang"), qs);

        List<String> ward = StreetUpwardMatcher.searchQueries("Phường Thống Nhất");
        assertEquals(List.of("Phường Thống Nhất"), ward);
    }

    @Test
    void pickBest_prefersExactThenCloser() {
        GeoRegion exactFar = region(1L, "ABC Street", 30, 120);
        GeoRegion prefixNear = region(2L, "ABC", 20.78, 105.35);
        StreetUpwardMatcher.ScoredHit hit = StreetUpwardMatcher.pickBest(
                List.of(exactFar, prefixNear), "ABC Street", 20.78, 105.35);
        assertNotNull(hit);
        assertEquals(1L, hit.region().getId());
        assertEquals("EXACT", hit.reason());
    }

    @Test
    void extractCountryCode_uppercases() {
        assertEquals("VN", StreetUpwardMatcher.extractCountryCode(Map.of("country_code", "vn")));
        assertNull(StreetUpwardMatcher.extractCountryCode(Map.of("country", "越南")));
    }

    @Test
    void normalize_fillsCommonFields() {
        Map<String, String> addr = new LinkedHashMap<>();
        addr.put("road", "Main St");
        addr.put("city", "Hanoi");
        addr.put("state", "Hanoi");
        addr.put("country", "Vietnam");
        addr.put("country_code", "vn");
        addr.put("postcode", "100000");
        var n = StreetUpwardMatcher.normalize(addr);
        assertEquals("Main St", n.getStreet());
        assertEquals("Hanoi", n.getCity());
        assertEquals("vn", n.getCountryCode());
        assertEquals("100000", n.getPostcode());
    }

    @Test
    void toAcceptLanguage_mapsZh() {
        assertEquals("zh-CN", ReverseGeocodeService.toAcceptLanguage("zh"));
        assertNull(ReverseGeocodeService.toAcceptLanguage(null));
        assertNull(ReverseGeocodeService.toAcceptLanguage(""));
        assertNull(ReverseGeocodeService.toAcceptLanguage("local"));
    }

    @Test
    void levelsStreetUpward_order() {
        assertEquals(List.of(5, 4, 3, 2, 1), StreetUpwardMatcher.levelsStreetUpward());
        assertTrue(StreetUpwardMatcher.candidatesForLevel(Map.of(), 5).isEmpty());
    }

    private static GeoRegion region(Long id, String name, double lat, double lon) {
        GeoRegion r = new GeoRegion();
        r.setId(id);
        r.setName(name);
        r.setLevel(5);
        r.setLatitude(BigDecimal.valueOf(lat));
        r.setLongitude(BigDecimal.valueOf(lon));
        return r;
    }
}

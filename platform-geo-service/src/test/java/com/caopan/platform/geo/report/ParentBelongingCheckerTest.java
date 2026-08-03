package com.caopan.platform.geo.report;

import com.caopan.platform.geo.entity.GeoRegion;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentBelongingCheckerTest {

    @Test
    void isUnderParent_displayNameContainsParentName() {
        GeoRegion parent = region("Hồ Chí Minh", "Ho Chi Minh", "胡志明");
        ParentBelongingChecker.GeocodeResult geocode = new ParentBelongingChecker.GeocodeResult(
                10.8, 106.6,
                "Quận 1, Hồ Chí Minh, Vietnam",
                Map.of("city", "Hồ Chí Minh"),
                "raw");
        assertTrue(ParentBelongingChecker.isUnderParent(parent, geocode));
    }

    @Test
    void isUnderParent_noMatch_returnsFalse() {
        GeoRegion parent = region("Hanoi", "Hanoi", "河内");
        ParentBelongingChecker.GeocodeResult geocode = new ParentBelongingChecker.GeocodeResult(
                10.8, 106.6,
                "Da Nang, Vietnam",
                Map.of("city", "Da Nang"),
                "raw");
        assertFalse(ParentBelongingChecker.isUnderParent(parent, geocode));
    }

    @Test
    void containsAnyName_requiresMinLength() {
        assertFalse(ParentBelongingChecker.containsAnyName("abc", java.util.List.of("a")));
        assertTrue(ParentBelongingChecker.containsAnyName("abc district", java.util.List.of("district")));
    }

    private static GeoRegion region(String name, String en, String ch) {
        GeoRegion r = new GeoRegion();
        r.setName(name);
        r.setNameEn(en);
        r.setNameCh(ch);
        return r;
    }
}

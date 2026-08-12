package com.caopan.platform.geo.report;

import com.caopan.platform.geo.entity.GeoRegion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbNearestReverseTest {

    @Test
    void pickNearest_prefersDeeperLevelWithinRadius() {
        GeoRegion street = region(4L, 4, 20.7815783, 105.3521674);
        GeoRegion ward = region(3L, 3, 20.7847216, 105.3437837);
        ReverseGeocodeService.NearestHit hit = ReverseGeocodeService.pickNearest(
                List.of(ward, street), 20.7815783, 105.3521674, 1.0);
        assertNotNull(hit);
        assertEquals(4L, hit.region().getId());
        assertEquals(0.0, hit.distanceKm(), 1e-6);
    }

    @Test
    void pickNearest_sameLevelChoosesCloser() {
        GeoRegion far = region(1L, 4, 20.79, 105.36);
        GeoRegion near = region(2L, 4, 20.7816, 105.3522);
        ReverseGeocodeService.NearestHit hit = ReverseGeocodeService.pickNearest(
                List.of(far, near), 20.7815783, 105.3521674, 5.0);
        assertNotNull(hit);
        assertEquals(2L, hit.region().getId());
    }

    @Test
    void pickNearest_outsideRadius_returnsNull() {
        GeoRegion far = region(1L, 4, 21.0, 106.0);
        assertNull(ReverseGeocodeService.pickNearest(List.of(far), 20.78, 105.35, 1.0));
    }

    @Test
    void pickNearestWithLevelCaps_prefersDistrictWithin5km() {
        GeoRegion state = region(2L, 2, 20.78, 105.35);
        GeoRegion district = region(3L, 3, 20.785, 105.355);
        ReverseGeocodeService.NearestHit hit = ReverseGeocodeService.pickNearestWithLevelCaps(
                List.of(state, district), 20.7815783, 105.3521674);
        assertNotNull(hit);
        assertEquals(3L, hit.region().getId());
    }

    @Test
    void envelopeWkt_isClosedPolygon() {
        String wkt = ReverseGeocodeService.envelopeWkt(20.78, 105.35, 1.0);
        assertTrue(wkt.startsWith("POLYGON(("));
        assertTrue(wkt.endsWith("))"));
    }

    @Test
    void reverseGrid_samePointStable() {
        long[] a = com.caopan.platform.geo.cache.GeoCacheKeys.reverseGrid(20.7815783, 105.3521674);
        long[] b = com.caopan.platform.geo.cache.GeoCacheKeys.reverseGrid(20.7815783, 105.3521674);
        assertEquals(a[0], b[0]);
        assertEquals(a[1], b[1]);
        String key = com.caopan.platform.geo.cache.GeoCacheKeys.reverse(a[0], a[1], "VN", "zh");
        assertTrue(key.contains("rev:VN:"));
    }

    private static GeoRegion region(Long id, int level, double lat, double lon) {
        GeoRegion r = new GeoRegion();
        r.setId(id);
        r.setLevel(level);
        r.setLatitude(BigDecimal.valueOf(lat));
        r.setLongitude(BigDecimal.valueOf(lon));
        r.setName("n" + id);
        return r;
    }
}

package com.caopan.platform.geo.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 地理距离计算（Haversine，GEO-002）。
 */
public final class GeoDistanceUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoDistanceUtil() {
    }

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    public static BigDecimal toDecimalKm(double km) {
        return BigDecimal.valueOf(km).setScale(3, RoundingMode.HALF_UP);
    }
}

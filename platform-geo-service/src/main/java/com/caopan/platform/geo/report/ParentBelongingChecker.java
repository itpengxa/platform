package com.caopan.platform.geo.report;

import com.caopan.platform.geo.entity.GeoRegion;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * 地图结果是否属于父级下的判定（GEO-002）。
 */
public final class ParentBelongingChecker {

    private ParentBelongingChecker() {
    }

    /**
     * 判定 geocode 结果是否属于 parent 之下。
     */
    public static boolean isUnderParent(GeoRegion parent, GeocodeResult geocode) {
        if (parent == null || geocode == null) {
            return false;
        }
        if (containsAnyName(geocode.displayName(), parentNames(parent))) {
            return true;
        }
        if (geocode.addressParts() != null) {
            for (String part : geocode.addressParts().values()) {
                if (containsAnyName(part, parentNames(parent))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Collection<String> parentNames(GeoRegion parent) {
        return java.util.List.of(
                parent.getName(),
                parent.getNameEn(),
                parent.getNameCh()).stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    static boolean containsAnyName(String text, Collection<String> names) {
        if (text == null || text.isBlank() || names.isEmpty()) {
            return false;
        }
        String hay = text.trim().toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.length() >= 2 && hay.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** 地图查询结果。 */
    public record GeocodeResult(
            double lat,
            double lng,
            String displayName,
            Map<String, String> addressParts,
            String rawSummary) {
    }
}

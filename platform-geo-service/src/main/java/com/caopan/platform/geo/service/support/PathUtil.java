package com.caopan.platform.geo.service.support;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2026-07-24 GEO-001 path 解析
 */
public final class PathUtil {

    private PathUtil() {
    }

    public static List<Long> parsePathIds(String path) {
        if (!StringUtils.hasText(path)) {
            return Collections.emptyList();
        }
        String[] parts = path.split("/");
        List<Long> ids = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ids;
    }
}

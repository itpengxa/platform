package com.caopan.platform.geo.access;

import com.caopan.platform.geo.config.runtime.EffectiveAccessLogSettings;
import com.caopan.platform.geo.entity.PlatformApiAccessStat;
import com.caopan.platform.geo.mapper.PlatformApiAccessStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 调用记录落库（GEO-001 / platform-geo-service）。
 * <p>由 {@code GeoAccessAspect} 在虚拟线程中调用，避免阻塞请求 RT。
 * 落库失败只打 warn，不影响主链路。</p>
 */
@Service
public class ApiAccessStatRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessStatRecorder.class);
    private static final Pattern KV = Pattern.compile("(\\w+)=([^,]+)");
    private static final Pattern JSON_NUM = Pattern.compile("\"(parentId|rootId|id)\"\\s*:\\s*(\\d+)");
    private static final Pattern JSON_CC = Pattern.compile("\"countryCode\"\\s*:\\s*\"([A-Za-z]{2})\"");
    private static final Pattern PATH_REGION_ID = Pattern.compile("/regions/(\\d+)(?:/|$)");

    private final PlatformApiAccessStatMapper mapper;
    private final EffectiveAccessLogSettings accessLogSettings;

    public ApiAccessStatRecorder(
            PlatformApiAccessStatMapper mapper,
            EffectiveAccessLogSettings accessLogSettings) {
        this.mapper = mapper;
        this.accessLogSettings = accessLogSettings;
    }

    public void record(
            String clientCode,
            LocalDateTime calledAt,
            String apiKey,
            String requestParams,
            boolean success,
            String errorType,
            int costMs) {
        if (!accessLogSettings.statEnabled()) {
            return;
        }
        int maxLen = accessLogSettings.paramsMaxLength();
        try {
            PlatformApiAccessStat row = new PlatformApiAccessStat();
            row.setClientCode(StringUtils.hasText(clientCode) ? clientCode : "anonymous");
            row.setCalledAt(calledAt == null ? LocalDateTime.now() : calledAt);
            row.setApiKey(apiKey == null ? "UNKNOWN" : truncate(apiKey, 128));
            row.setRequestParams(truncate(requestParams, maxLen));
            row.setSuccess(success ? 1 : 0);
            row.setErrorType(truncate(errorType, 128));
            row.setCostMs(Math.max(costMs, 0));
            applyDimensions(row, requestParams, apiKey);
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("access stat insert failed, api={}, err={}", apiKey, e.toString());
        }
    }

    static void applyDimensions(PlatformApiAccessStat row, String requestParams) {
        applyDimensions(row, requestParams, null);
    }

    static void applyDimensions(PlatformApiAccessStat row, String requestParams, String apiKey) {
        String params = requestParams == null ? "" : requestParams;
        String cc = extractValue(params, "countryCode");
        if (!StringUtils.hasText(cc)) {
            Matcher jm = JSON_CC.matcher(params);
            if (jm.find()) {
                cc = jm.group(1);
            }
        }
        if (StringUtils.hasText(cc) && cc.length() == 2) {
            row.setCountryCode(cc.trim().toUpperCase());
        }
        Long regionId = firstLong(params, "parentId", "rootId", "id");
        if (regionId == null) {
            Matcher jm = JSON_NUM.matcher(params);
            // 优先 parentId / rootId，再 id
            Long parent = null;
            Long root = null;
            Long id = null;
            while (jm.find()) {
                String k = jm.group(1);
                long v = Long.parseLong(jm.group(2));
                if (v <= 0) {
                    continue;
                }
                switch (k) {
                    case "parentId" -> parent = v;
                    case "rootId" -> root = v;
                    case "id" -> id = v;
                    default -> {
                    }
                }
            }
            regionId = parent != null ? parent : (root != null ? root : id);
        }
        if (regionId == null && StringUtils.hasText(apiKey)) {
            Matcher pm = PATH_REGION_ID.matcher(apiKey);
            if (pm.find()) {
                try {
                    long v = Long.parseLong(pm.group(1));
                    if (v > 0) {
                        regionId = v;
                    }
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        if (regionId != null && regionId > 0) {
            row.setRegionId(regionId);
        }
        Integer level = parseInt(extractValue(params, "level"));
        if (level != null && level >= 1 && level <= 5) {
            row.setRegionLevel(level);
        }
    }

    private static String extractValue(String params, String key) {
        Matcher m = KV.matcher(params);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                String v = m.group(2).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    private static Long firstLong(String params, String... keys) {
        for (String key : keys) {
            String v = extractValue(params, key);
            if (StringUtils.hasText(v)) {
                try {
                    long n = Long.parseLong(v);
                    if (n > 0) {
                        return n;
                    }
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return null;
    }

    private static Integer parseInt(String v) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

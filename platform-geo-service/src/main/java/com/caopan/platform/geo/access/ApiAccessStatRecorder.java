package com.caopan.platform.geo.access;

import com.caopan.platform.geo.config.GeoAccessLogProperties;
import com.caopan.platform.geo.entity.PlatformApiAccessStat;
import com.caopan.platform.geo.mapper.PlatformApiAccessStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * API 调用记录落库（GEO-001 / platform-geo-service）。
 * <p>由 {@code GeoAccessAspect} 在虚拟线程中调用，避免阻塞请求 RT。
 * 落库失败只打 warn，不影响主链路。</p>
 */
@Service
public class ApiAccessStatRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessStatRecorder.class);

    private final PlatformApiAccessStatMapper mapper;
    private final GeoAccessLogProperties accessLogProperties;

    public ApiAccessStatRecorder(
            PlatformApiAccessStatMapper mapper,
            GeoAccessLogProperties accessLogProperties) {
        this.mapper = mapper;
        this.accessLogProperties = accessLogProperties;
    }

    public void record(
            String clientCode,
            LocalDateTime calledAt,
            String apiKey,
            String requestParams,
            boolean success,
            String errorType,
            int costMs) {
        if (!accessLogProperties.statEnabled()) {
            return;
        }
        int maxLen = accessLogProperties.resolvedParamsMaxLength();
        try {
            PlatformApiAccessStat row = new PlatformApiAccessStat();
            row.setClientCode(StringUtils.hasText(clientCode) ? clientCode : "anonymous");
            row.setCalledAt(calledAt == null ? LocalDateTime.now() : calledAt);
            row.setApiKey(apiKey == null ? "UNKNOWN" : truncate(apiKey, 128));
            row.setRequestParams(truncate(requestParams, maxLen));
            row.setSuccess(success ? 1 : 0);
            row.setErrorType(truncate(errorType, 128));
            row.setCostMs(Math.max(costMs, 0));
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("access stat insert failed, api={}, err={}", apiKey, e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

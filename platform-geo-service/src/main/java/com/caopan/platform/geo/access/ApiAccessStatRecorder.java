package com.caopan.platform.geo.access;

import com.caopan.platform.geo.entity.PlatformApiAccessStat;
import com.caopan.platform.geo.mapper.PlatformApiAccessStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * API 调用记录落库（GEO-001 / platform-geo-service）。
 * <p>由 {@code GeoAccessAspect} 在虚拟线程中调用，避免阻塞请求 RT。
 * 开关 {@code platform.geo.access-log.stat-enabled}；入参超长按
 * {@code params-max-length} 截断。落库失败只打 warn，不影响主链路。</p>
 */
@Service
public class ApiAccessStatRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessStatRecorder.class);

    private final PlatformApiAccessStatMapper mapper;
    private final boolean enabled;
    private final int paramsMaxLength;

    /**
     * @param mapper           调用记录 Mapper
     * @param enabled          是否写入统计表
     * @param paramsMaxLength  入参快照最大字符数
     */
    public ApiAccessStatRecorder(
            PlatformApiAccessStatMapper mapper,
            @Value("${platform.geo.access-log.stat-enabled:true}") boolean enabled,
            @Value("${platform.geo.access-log.params-max-length:2048}") int paramsMaxLength) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.paramsMaxLength = Math.max(paramsMaxLength, 64);
    }

    /**
     * 写入一条调用记录。
     *
     * @param clientCode     调用来源；空则记 anonymous
     * @param calledAt       调用时间
     * @param apiKey         METHOD + URI
     * @param requestParams  入参快照
     * @param success        true=无异常成功；false=抛异常（含 BizException）
     * @param errorType      失败时异常简名，成功传 null
     * @param costMs         耗时毫秒
     */
    public void record(
            String clientCode,
            LocalDateTime calledAt,
            String apiKey,
            String requestParams,
            boolean success,
            String errorType,
            int costMs) {
        if (!enabled) {
            return;
        }
        try {
            PlatformApiAccessStat row = new PlatformApiAccessStat();
            row.setClientCode(StringUtils.hasText(clientCode) ? clientCode : "anonymous");
            row.setCalledAt(calledAt == null ? LocalDateTime.now() : calledAt);
            row.setApiKey(apiKey == null ? "UNKNOWN" : truncate(apiKey, 128));
            row.setRequestParams(truncate(requestParams, paramsMaxLength));
            row.setSuccess(success ? 1 : 0);
            row.setErrorType(truncate(errorType, 128));
            row.setCostMs(Math.max(costMs, 0));
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("access stat insert failed, api={}, err={}", apiKey, e.toString());
        }
    }

    /**
     * 截断字符串至 max 长度。
     *
     * @param s   原文，可为 null
     * @param max 最大长度
     * @return 截断结果或 null
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}

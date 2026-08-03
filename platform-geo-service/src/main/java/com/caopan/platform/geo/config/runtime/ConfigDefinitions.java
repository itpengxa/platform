package com.caopan.platform.geo.config.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可管理配置项注册表。
 */
public final class ConfigDefinitions {

    public static final String CLEAR_VALUE = "__CLEAR__";

    private static final Map<String, ConfigDefinition> ALL = new LinkedHashMap<>();

    static {
        // report
        reg(ConfigDefinition.enums("platform.geo.report.geocode-provider", "report", true,
                "地理编码提供商", List.of("nominatim", "google")));
        reg(ConfigDefinition.of("platform.geo.report.google-api-key", "report", ConfigValueType.SECRET,
                true, true, true, "Google Maps API Key"));
        reg(ConfigDefinition.range("platform.geo.report.max-parent-distance-km", "report", ConfigValueType.DOUBLE,
                true, "上报父节点最大距离(km)", 0.1, 500));
        reg(ConfigDefinition.of("platform.geo.report.auto-create-enabled", "report", ConfigValueType.BOOL,
                false, true, true, "命中规则后是否自动创建区划"));
        reg(ConfigDefinition.range("platform.geo.report.rate-limit-per-token-per-hour", "report", ConfigValueType.INT,
                true, "每接入方每小时上报上限", 1, 100000));
        reg(ConfigDefinition.of("platform.geo.report.nominatim-user-agent", "report", ConfigValueType.STRING,
                false, true, true, "Nominatim User-Agent"));

        // rate-limit
        reg(ConfigDefinition.of("platform.geo.rate-limit.enabled", "rate-limit", ConfigValueType.BOOL,
                false, true, true, "IP 限流开关"));
        reg(ConfigDefinition.of("platform.geo.rate-limit.trust-forwarded-headers", "rate-limit", ConfigValueType.BOOL,
                false, true, true, "是否信任 X-Forwarded-For"));
        reg(ConfigDefinition.of("platform.geo.rate-limit.fail-closed", "rate-limit", ConfigValueType.BOOL,
                false, true, true, "Redis 不可用时是否拒绝请求"));
        reg(ConfigDefinition.range("platform.geo.rate-limit.default-interval-ms", "rate-limit", ConfigValueType.LONG,
                true, "默认接口间隔(ms)", 1, 600000));
        reg(ConfigDefinition.range("platform.geo.rate-limit.search-interval-ms", "rate-limit", ConfigValueType.LONG,
                true, "搜索接口间隔(ms)", 1, 600000));
        reg(ConfigDefinition.range("platform.geo.rate-limit.tree-interval-ms", "rate-limit", ConfigValueType.LONG,
                true, "树接口间隔(ms)", 1, 600000));

        // auth
        reg(ConfigDefinition.of("platform.geo.auth.enabled", "auth", ConfigValueType.BOOL,
                false, true, true, "用户 API Token 鉴权开关（关闭有风险）"));
        reg(ConfigDefinition.of("platform.geo.auth.issue-secret", "auth", ConfigValueType.SECRET,
                true, true, true, "Token 签发密钥 X-Platform-Issue-Secret"));
        reg(ConfigDefinition.of("platform.geo.auth.redis-token-sync-enabled", "auth", ConfigValueType.BOOL,
                false, true, true, "Token 校验是否同步 Redis"));
        reg(ConfigDefinition.range("platform.geo.auth.valid-ttl-days", "auth", ConfigValueType.LONG,
                true, "Token Redis 有效缓存天数", 1, 3650));
        reg(ConfigDefinition.readonly("platform.geo.auth.issue-lock-key-prefix", "auth", ConfigValueType.STRING,
                false, "签发锁 Redis 前缀（只读）"));
        reg(ConfigDefinition.readonly("platform.geo.auth.valid-key-prefix", "auth", ConfigValueType.STRING,
                false, "有效 Token Redis 前缀（只读）"));

        // access-log
        reg(ConfigDefinition.of("platform.geo.access-log.args-enabled", "access-log", ConfigValueType.BOOL,
                false, true, true, "记录请求参数"));
        reg(ConfigDefinition.of("platform.geo.access-log.exception-enabled", "access-log", ConfigValueType.BOOL,
                false, true, true, "记录异常"));
        reg(ConfigDefinition.of("platform.geo.access-log.stat-enabled", "access-log", ConfigValueType.BOOL,
                false, true, true, "写入调用统计"));
        reg(ConfigDefinition.range("platform.geo.access-log.params-max-length", "access-log", ConfigValueType.INT,
                true, "参数截断长度", 64, 65535));

        // admin
        reg(ConfigDefinition.readonly("platform.geo.admin.enabled", "admin", ConfigValueType.BOOL,
                false, "管理端总开关（只读，改需重启）"));
        reg(ConfigDefinition.readonly("platform.geo.admin.path-prefix", "admin", ConfigValueType.STRING,
                false, "管理端路径前缀（只读，改需重启）"));
        reg(ConfigDefinition.of("platform.geo.admin.secret", "admin", ConfigValueType.SECRET,
                true, true, true, "兼容旧 X-Admin-Secret"));
        reg(ConfigDefinition.range("platform.geo.admin.session-ttl-days", "admin", ConfigValueType.INT,
                true, "管理员会话有效天数", 1, 365));

        // cache
        reg(ConfigDefinition.readonly("platform.geo.cache.redis-enabled", "cache", ConfigValueType.BOOL,
                false, "是否启用 Redis L2（改需重启）"));
        reg(ConfigDefinition.readonly("platform.geo.cache.l1-maximum-size", "cache", ConfigValueType.LONG,
                false, "L1 最大条目（改需重启）"));
        reg(ConfigDefinition.range("platform.geo.cache.l1-ttl-minutes", "cache", ConfigValueType.LONG,
                true, "L1 TTL(分钟)，下次写缓存生效", 1, 10080));
        reg(ConfigDefinition.range("platform.geo.cache.countries-ttl-hours", "cache", ConfigValueType.LONG,
                true, "国家列表 TTL(小时)", 1, 720));
        reg(ConfigDefinition.range("platform.geo.cache.children-ttl-hours", "cache", ConfigValueType.LONG,
                true, "子节点 TTL(小时)", 1, 720));
        reg(ConfigDefinition.range("platform.geo.cache.path-ttl-hours", "cache", ConfigValueType.LONG,
                true, "祖先链 TTL(小时)", 1, 720));
        reg(ConfigDefinition.range("platform.geo.cache.region-ttl-hours", "cache", ConfigValueType.LONG,
                true, "区划详情 TTL(小时)", 1, 720));
        reg(ConfigDefinition.range("platform.geo.cache.tree-ttl-hours", "cache", ConfigValueType.LONG,
                true, "树 TTL(小时)", 1, 720));
        reg(ConfigDefinition.range("platform.geo.cache.jitter-seconds", "cache", ConfigValueType.LONG,
                true, "TTL 抖动上限(秒)", 0, 3600));
        reg(ConfigDefinition.range("platform.geo.cache.negative-ttl-seconds", "cache", ConfigValueType.LONG,
                true, "负缓存 TTL(秒)", 1, 3600));
        reg(ConfigDefinition.range("platform.geo.cache.tree-max-rows", "cache", ConfigValueType.INT,
                true, "树查询最大行数", 100, 500000));
        reg(ConfigDefinition.range("platform.geo.cache.tree-country-max-depth", "cache", ConfigValueType.INT,
                true, "国家级树最大 depth", 1, 5));
    }

    private ConfigDefinitions() {
    }

    private static void reg(ConfigDefinition def) {
        ALL.put(def.key(), def);
    }

    public static Optional<ConfigDefinition> find(String key) {
        return Optional.ofNullable(ALL.get(key));
    }

    public static Collection<ConfigDefinition> all() {
        return ALL.values();
    }

    public static List<ConfigDefinition> byGroup(String group) {
        return ALL.values().stream().filter(d -> d.group().equals(group)).toList();
    }

    public static List<String> groups() {
        return ALL.values().stream().map(ConfigDefinition::group).distinct().toList();
    }
}

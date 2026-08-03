package com.caopan.platform.geo.config.runtime;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.config.GeoAccessLogProperties;
import com.caopan.platform.geo.config.GeoAdminProperties;
import com.caopan.platform.geo.config.GeoAuthProperties;
import com.caopan.platform.geo.config.GeoReportProperties;
import com.caopan.platform.geo.entity.PlatformRuntimeConfig;
import com.caopan.platform.geo.entity.PlatformRuntimeConfigAudit;
import com.caopan.platform.geo.mapper.PlatformRuntimeConfigAuditMapper;
import com.caopan.platform.geo.mapper.PlatformRuntimeConfigMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 运行时配置：yml 默认 ∪ DB 覆盖，热生效。
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final EffectiveConfigRegistry registry;
    private final ConfigCrypto crypto;
    private final PlatformRuntimeConfigMapper configMapper;
    private final PlatformRuntimeConfigAuditMapper auditMapper;
    private final ConfigChangeBroadcaster broadcaster;

    private final Environment environment;
    private final GeoReportProperties reportDefaults;
    private final GeoAuthProperties authDefaults;
    private final GeoAccessLogProperties accessLogDefaults;
    private final GeoAdminProperties adminDefaults;
    private final GeoCacheProperties cacheDefaults;

    public RuntimeConfigService(
            EffectiveConfigRegistry registry,
            ConfigCrypto crypto,
            PlatformRuntimeConfigMapper configMapper,
            PlatformRuntimeConfigAuditMapper auditMapper,
            ConfigChangeBroadcaster broadcaster,
            Environment environment,
            GeoReportProperties reportDefaults,
            GeoAuthProperties authDefaults,
            GeoAccessLogProperties accessLogDefaults,
            GeoAdminProperties adminDefaults,
            GeoCacheProperties cacheDefaults) {
        this.registry = registry;
        this.crypto = crypto;
        this.configMapper = configMapper;
        this.auditMapper = auditMapper;
        this.broadcaster = broadcaster;
        this.environment = environment;
        this.reportDefaults = reportDefaults;
        this.authDefaults = authDefaults;
        this.accessLogDefaults = accessLogDefaults;
        this.adminDefaults = adminDefaults;
        this.cacheDefaults = cacheDefaults;
    }

    @PostConstruct
    public void init() {
        broadcaster.setOnRemoteChange(from -> {
            log.info("reload runtime config from remote instance {}", from);
            reloadFromDb();
        });
        reloadFromDb();
    }

    public synchronized void reloadFromDb() {
        Map<String, String> defaults = buildDefaults();
        Map<String, String> effective = new HashMap<>(defaults);
        Set<String> overridden = new HashSet<>();
        List<PlatformRuntimeConfig> rows;
        try {
            rows = configMapper.findAll();
        } catch (Exception e) {
            log.warn("platform_runtime_config unavailable, using yml defaults only: {}", e.getMessage());
            registry.replaceAll(effective, overridden);
            return;
        }
        if (rows != null) {
            for (PlatformRuntimeConfig row : rows) {
                ConfigDefinition def = ConfigDefinitions.find(row.getConfigKey()).orElse(null);
                boolean secret = def != null && def.secret()
                        || (row.getSecret() != null && row.getSecret() == 1);
                try {
                    String plain = crypto.decryptIfNeeded(row.getConfigValue(), secret);
                    if (plain != null) {
                        effective.put(row.getConfigKey(), plain);
                        overridden.add(row.getConfigKey());
                    }
                } catch (Exception e) {
                    log.error("skip broken config key={}: {}", row.getConfigKey(), e.getMessage());
                }
            }
        }
        registry.replaceAll(effective, overridden);
        log.info("runtime config loaded, keys={}, overridden={}", effective.size(), overridden.size());
    }

    public List<ConfigItemView> listAll() {
        return ConfigDefinitions.all().stream().map(this::toView).toList();
    }

    public List<ConfigItemView> listGroup(String group) {
        return ConfigDefinitions.byGroup(group).stream().map(this::toView).toList();
    }

    public Map<String, List<ConfigItemView>> listGrouped() {
        Map<String, List<ConfigItemView>> map = new LinkedHashMap<>();
        for (String g : ConfigDefinitions.groups()) {
            map.put(g, listGroup(g));
        }
        return map;
    }

    @Transactional
    public List<String> save(List<ConfigWriteItem> items, String updatedBy) {
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        List<String> needRestart = new ArrayList<>();
        for (ConfigWriteItem item : items) {
            if (item == null || !StringUtils.hasText(item.key())) {
                continue;
            }
            ConfigDefinition def = ConfigDefinitions.find(item.key())
                    .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID));
            if (!def.writable()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            String raw = item.value();
            if (raw == null) {
                continue;
            }
            if (def.secret() && raw.isEmpty()) {
                // empty secret = keep unchanged
                continue;
            }
            if (ConfigDefinitions.CLEAR_VALUE.equals(raw)) {
                resetOne(def, updatedBy);
                if (!def.hotReload()) {
                    needRestart.add(def.key());
                }
                continue;
            }
            String normalized = validateAndNormalize(def, raw);
            String current = registry.get(def.key());
            if (current == null) {
                current = buildDefaults().get(def.key());
            }
            // 与当前生效值相同则跳过，避免无变更写库/误标 DB 覆盖
            if (Objects.equals(normalized, current == null ? "" : current)) {
                continue;
            }
            upsertOne(def, normalized, updatedBy);
            if (!def.hotReload()) {
                needRestart.add(def.key());
            }
        }
        reloadFromDb();
        broadcaster.publish();
        return needRestart;
    }

    @Transactional
    public void reset(List<String> keys, String updatedBy) {
        if (keys == null || keys.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        for (String key : keys) {
            ConfigDefinition def = ConfigDefinitions.find(key)
                    .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID));
            resetOne(def, updatedBy);
        }
        reloadFromDb();
        broadcaster.publish();
    }

    private void resetOne(ConfigDefinition def, String updatedBy) {
        PlatformRuntimeConfig existing = configMapper.findByKey(def.key());
        String oldMasked = existing == null ? null : maskForAudit(def, safeDecrypt(existing));
        configMapper.deleteByKey(def.key());
        writeAudit(def.key(), oldMasked, null, "RESET", updatedBy);
    }

    private void upsertOne(ConfigDefinition def, String plain, String updatedBy) {
        if (def.secret() && !crypto.isCryptoReady() && crypto.isOnlineProfile()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        PlatformRuntimeConfig existing = configMapper.findByKey(def.key());
        String oldMasked = existing == null ? null : maskForAudit(def, safeDecrypt(existing));
        String stored = crypto.encryptIfNeeded(plain, def.secret());
        PlatformRuntimeConfig row = new PlatformRuntimeConfig();
        row.setConfigKey(def.key());
        row.setConfigValue(stored);
        row.setValueType(def.type().name());
        row.setConfigGroup(def.group());
        row.setSecret(def.secret() ? 1 : 0);
        row.setDescription(def.description());
        row.setUpdatedBy(updatedBy);
        row.setUpdatedAt(LocalDateTime.now());
        configMapper.upsert(row);
        writeAudit(def.key(), oldMasked, maskForAudit(def, plain), "UPSERT", updatedBy);
    }

    private String safeDecrypt(PlatformRuntimeConfig row) {
        try {
            return crypto.decryptIfNeeded(row.getConfigValue(), row.getSecret() != null && row.getSecret() == 1);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeAudit(String key, String oldV, String newV, String action, String by) {
        PlatformRuntimeConfigAudit a = new PlatformRuntimeConfigAudit();
        a.setConfigKey(key);
        a.setOldValue(oldV);
        a.setNewValue(newV);
        a.setAction(action);
        a.setUpdatedBy(by);
        a.setCreatedAt(LocalDateTime.now());
        try {
            auditMapper.insert(a);
        } catch (Exception e) {
            log.warn("config audit insert failed: {}", e.getMessage());
        }
    }

    private ConfigItemView toView(ConfigDefinition def) {
        String effective = registry.get(def.key());
        if (effective == null) {
            effective = buildDefaults().get(def.key());
        }
        boolean overridden = registry.isOverridden(def.key());
        String display;
        boolean hasValue = StringUtils.hasText(effective);
        boolean masked = false;
        if (def.secret()) {
            masked = true;
            display = hasValue ? "******" : "";
        } else {
            display = effective == null ? "" : effective;
        }
        return new ConfigItemView(
                def.key(), def.group(), def.type().name(), def.secret(), def.hotReload(), def.writable(),
                def.description(), def.min(), def.max(), def.enums(),
                display, hasValue, masked, overridden,
                overridden ? "DB" : "DEFAULT"
        );
    }

    private String validateAndNormalize(ConfigDefinition def, String raw) {
        String v = raw.trim();
        return switch (def.type()) {
            case BOOL -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                    throw new BizException(ErrorCode.PARAM_INVALID);
                }
                yield Boolean.parseBoolean(v) ? "true" : "false";
            }
            case INT -> {
                int n = Integer.parseInt(v);
                checkRange(def, n);
                yield String.valueOf(n);
            }
            case LONG -> {
                long n = Long.parseLong(v);
                checkRange(def, n);
                yield String.valueOf(n);
            }
            case DOUBLE -> {
                double n = Double.parseDouble(v);
                checkRange(def, n);
                yield String.valueOf(n);
            }
            case STRING, SECRET -> {
                if (def.enums() != null && !def.enums().isEmpty()
                        && def.enums().stream().noneMatch(e -> e.equalsIgnoreCase(v))) {
                    throw new BizException(ErrorCode.PARAM_INVALID);
                }
                yield v;
            }
        };
    }

    private static void checkRange(ConfigDefinition def, double n) {
        if (def.min() != null && n < def.min()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (def.max() != null && n > def.max()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private static String maskForAudit(ConfigDefinition def, String plain) {
        if (plain == null) {
            return null;
        }
        if (def.secret()) {
            return StringUtils.hasText(plain) ? "******" : "";
        }
        return plain;
    }

    private Map<String, String> buildDefaults() {
        Map<String, String> m = new HashMap<>();
        m.put("platform.geo.report.geocode-provider", reportDefaults.normalizedProvider());
        m.put("platform.geo.report.google-api-key", Objects.toString(reportDefaults.googleApiKey(), ""));
        m.put("platform.geo.report.max-parent-distance-km", String.valueOf(reportDefaults.maxParentDistanceKm()));
        m.put("platform.geo.report.auto-create-enabled", String.valueOf(reportDefaults.autoCreateEnabled()));
        m.put("platform.geo.report.rate-limit-per-token-per-hour", String.valueOf(reportDefaults.rateLimitPerTokenPerHour()));
        m.put("platform.geo.report.nominatim-user-agent", Objects.toString(reportDefaults.nominatimUserAgent(), ""));

        // rate-limit Properties 位于 bootstrap，此处用 Environment 取 yml 默认
        m.put("platform.geo.rate-limit.enabled", env("platform.geo.rate-limit.enabled", "true"));
        m.put("platform.geo.rate-limit.trust-forwarded-headers",
                env("platform.geo.rate-limit.trust-forwarded-headers", "false"));
        m.put("platform.geo.rate-limit.fail-closed", env("platform.geo.rate-limit.fail-closed", "false"));
        m.put("platform.geo.rate-limit.default-interval-ms",
                env("platform.geo.rate-limit.default-interval-ms", "1000"));
        m.put("platform.geo.rate-limit.search-interval-ms",
                env("platform.geo.rate-limit.search-interval-ms", "1000"));
        m.put("platform.geo.rate-limit.tree-interval-ms",
                env("platform.geo.rate-limit.tree-interval-ms", "2000"));

        m.put("platform.geo.auth.enabled", String.valueOf(authDefaults.enabled()));
        m.put("platform.geo.auth.issue-secret", Objects.toString(authDefaults.issueSecret(), ""));
        m.put("platform.geo.auth.redis-token-sync-enabled", String.valueOf(authDefaults.redisTokenSyncEnabled()));
        m.put("platform.geo.auth.valid-ttl-days", String.valueOf(authDefaults.validTtlDays()));
        m.put("platform.geo.auth.issue-lock-key-prefix", authDefaults.issueLockKeyPrefix());
        m.put("platform.geo.auth.valid-key-prefix", authDefaults.validKeyPrefix());
        m.put("platform.geo.auth.issue-lock-seconds", String.valueOf(authDefaults.issueLockSeconds()));
        m.put("platform.geo.auth.issue-lock-retry-times", String.valueOf(authDefaults.issueLockRetryTimes()));
        m.put("platform.geo.auth.issue-lock-retry-ms", String.valueOf(authDefaults.issueLockRetryMs()));

        m.put("platform.geo.access-log.args-enabled", String.valueOf(accessLogDefaults.argsEnabled()));
        m.put("platform.geo.access-log.exception-enabled", String.valueOf(accessLogDefaults.exceptionEnabled()));
        m.put("platform.geo.access-log.stat-enabled", String.valueOf(accessLogDefaults.statEnabled()));
        m.put("platform.geo.access-log.params-max-length", String.valueOf(accessLogDefaults.paramsMaxLength()));

        m.put("platform.geo.admin.enabled", String.valueOf(adminDefaults.enabled()));
        m.put("platform.geo.admin.path-prefix", adminDefaults.normalizedPathPrefix());
        m.put("platform.geo.admin.secret", Objects.toString(adminDefaults.secret(), ""));
        m.put("platform.geo.admin.session-ttl-days", String.valueOf(adminDefaults.sessionTtlDays()));

        m.put("platform.geo.cache.redis-enabled", String.valueOf(cacheDefaults.redisEnabled()));
        m.put("platform.geo.cache.l1-maximum-size", String.valueOf(cacheDefaults.l1MaximumSize()));
        m.put("platform.geo.cache.l1-ttl-minutes", String.valueOf(cacheDefaults.l1TtlMinutes()));
        m.put("platform.geo.cache.countries-ttl-hours", String.valueOf(cacheDefaults.countriesTtlHours()));
        m.put("platform.geo.cache.children-ttl-hours", String.valueOf(cacheDefaults.childrenTtlHours()));
        m.put("platform.geo.cache.path-ttl-hours", String.valueOf(cacheDefaults.pathTtlHours()));
        m.put("platform.geo.cache.region-ttl-hours", String.valueOf(cacheDefaults.regionTtlHours()));
        m.put("platform.geo.cache.tree-ttl-hours", String.valueOf(cacheDefaults.treeTtlHours()));
        m.put("platform.geo.cache.jitter-seconds", String.valueOf(cacheDefaults.jitterSeconds()));
        m.put("platform.geo.cache.negative-ttl-seconds", String.valueOf(cacheDefaults.negativeTtlSeconds()));
        m.put("platform.geo.cache.tree-max-rows", String.valueOf(cacheDefaults.treeMaxRows()));
        m.put("platform.geo.cache.tree-country-max-depth", String.valueOf(cacheDefaults.treeCountryMaxDepth()));
        return m;
    }

    private String env(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    public record ConfigWriteItem(String key, String value) {
    }

    public record ConfigItemView(
            String key,
            String group,
            String valueType,
            boolean secret,
            boolean hotReload,
            boolean writable,
            String description,
            Double min,
            Double max,
            List<String> enums,
            String value,
            boolean hasValue,
            boolean masked,
            boolean overridden,
            String source
    ) {
    }
}

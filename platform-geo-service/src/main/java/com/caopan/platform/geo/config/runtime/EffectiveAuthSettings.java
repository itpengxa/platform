package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

@Component
public class EffectiveAuthSettings {

    private final EffectiveConfigRegistry registry;

    public EffectiveAuthSettings(EffectiveConfigRegistry registry) {
        this.registry = registry;
    }

    public boolean enabled() {
        return registry.getBool("platform.geo.auth.enabled", false);
    }

    public String issueSecret() {
        return registry.getOrDefault("platform.geo.auth.issue-secret", "");
    }

    public String normalizedIssueSecret() {
        String s = issueSecret();
        return s == null ? "" : s.trim();
    }

    public boolean redisTokenSyncEnabled() {
        return registry.getBool("platform.geo.auth.redis-token-sync-enabled", true);
    }

    public long validTtlDays() {
        return Math.max(registry.getLong("platform.geo.auth.valid-ttl-days", 365L), 1L);
    }

    public String issueLockKeyPrefix() {
        return registry.getOrDefault("platform.geo.auth.issue-lock-key-prefix", "platform:auth:issue-lock:");
    }

    public String validKeyPrefix() {
        return registry.getOrDefault("platform.geo.auth.valid-key-prefix", "platform:auth:valid:");
    }

    public long issueLockSeconds() {
        return registry.getLong("platform.geo.auth.issue-lock-seconds", 60L);
    }

    public int issueLockRetryTimes() {
        return registry.getInt("platform.geo.auth.issue-lock-retry-times", 8);
    }

    public long issueLockRetryMs() {
        return registry.getLong("platform.geo.auth.issue-lock-retry-ms", 50L);
    }
}

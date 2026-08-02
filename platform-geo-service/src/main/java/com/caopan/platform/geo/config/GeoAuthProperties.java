package com.caopan.platform.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token 鉴权配置（JDK21 record + 构造器绑定）。
 */
@ConfigurationProperties(prefix = "platform.geo.auth")
public record GeoAuthProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String issueSecret,
        @DefaultValue("true") boolean redisTokenSyncEnabled,
        @DefaultValue("platform:auth:issue-lock:") String issueLockKeyPrefix,
        @DefaultValue("platform:auth:valid:") String validKeyPrefix,
        @DefaultValue("60") long issueLockSeconds,
        @DefaultValue("8") int issueLockRetryTimes,
        @DefaultValue("50") long issueLockRetryMs,
        @DefaultValue("365") long validTtlDays
) {
    public String normalizedIssueSecret() {
        return issueSecret == null ? "" : issueSecret.trim();
    }
}

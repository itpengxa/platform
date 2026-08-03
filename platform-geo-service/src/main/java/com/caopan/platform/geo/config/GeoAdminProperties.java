package com.caopan.platform.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 管理端配置（GEO-002）。
 * <p>鉴权改为管理员账号密码登录；{@code secret} 仅作兼容过渡（可选），优先会话 Token。</p>
 */
@ConfigurationProperties(prefix = "platform.geo.admin")
public record GeoAdminProperties(
        @DefaultValue("true") boolean enabled,
        /** 兼容旧 X-Admin-Secret，可空 */
        @DefaultValue("") String secret,
        @DefaultValue("/admin") String pathPrefix,
        @DefaultValue("admin") String bootstrapUsername,
        @DefaultValue("admin") String bootstrapPassword,
        @DefaultValue("7") int sessionTtlDays
) {
    public String normalizedSecret() {
        return secret == null ? "" : secret.trim();
    }

    public String normalizedPathPrefix() {
        String p = pathPrefix == null || pathPrefix.isBlank() ? "/admin" : pathPrefix.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public String normalizedBootstrapUsername() {
        String u = bootstrapUsername == null ? "admin" : bootstrapUsername.trim();
        return u.isEmpty() ? "admin" : u;
    }

    public int resolvedSessionTtlDays() {
        return Math.max(sessionTtlDays, 1);
    }
}

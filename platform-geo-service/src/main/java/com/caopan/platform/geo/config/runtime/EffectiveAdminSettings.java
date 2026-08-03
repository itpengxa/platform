package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

@Component
public class EffectiveAdminSettings {

    private final EffectiveConfigRegistry registry;

    public EffectiveAdminSettings(EffectiveConfigRegistry registry) {
        this.registry = registry;
    }

    public boolean enabled() {
        return registry.getBool("platform.geo.admin.enabled", true);
    }

    public String secret() {
        return registry.getOrDefault("platform.geo.admin.secret", "");
    }

    public String normalizedSecret() {
        String s = secret();
        return s == null ? "" : s.trim();
    }

    public String pathPrefix() {
        return registry.getOrDefault("platform.geo.admin.path-prefix", "/admin");
    }

    public String normalizedPathPrefix() {
        String p = pathPrefix();
        if (p == null || p.isBlank()) {
            p = "/admin";
        }
        p = p.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public int sessionTtlDays() {
        return Math.max(registry.getInt("platform.geo.admin.session-ttl-days", 7), 1);
    }
}

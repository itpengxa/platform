package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

@Component
public class EffectiveAccessLogSettings {

    private final EffectiveConfigRegistry registry;

    public EffectiveAccessLogSettings(EffectiveConfigRegistry registry) {
        this.registry = registry;
    }

    public boolean argsEnabled() {
        return registry.getBool("platform.geo.access-log.args-enabled", true);
    }

    public boolean exceptionEnabled() {
        return registry.getBool("platform.geo.access-log.exception-enabled", true);
    }

    public boolean statEnabled() {
        return registry.getBool("platform.geo.access-log.stat-enabled", true);
    }

    public int paramsMaxLength() {
        return Math.max(registry.getInt("platform.geo.access-log.params-max-length", 2048), 64);
    }
}

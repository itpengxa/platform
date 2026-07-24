package com.caopan.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动门禁：online/prod 开启鉴权时必须配置非空 Token，避免空口令误开放。
 */
@Component
public class GeoAuthStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoAuthStartupGuard.class);

    private final Environment environment;
    private final boolean authEnabled;
    private final String authToken;

    public GeoAuthStartupGuard(
            Environment environment,
            @Value("${platform.geo.auth.enabled:false}") boolean authEnabled,
            @Value("${platform.geo.auth.token:}") String authToken) {
        this.environment = environment;
        this.authEnabled = authEnabled;
        this.authToken = authToken == null ? "" : authToken.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!authEnabled) {
            log.warn("platform.geo.auth.enabled=false — geo API 未启用内部 Token（仅适用于内网 test）");
            return;
        }
        if (!StringUtils.hasText(authToken)) {
            throw new IllegalStateException(
                    "platform.geo.auth.enabled=true 但 token 为空，请设置 GEO_INTERNAL_TOKEN / platform.geo.auth.token");
        }
        if (authToken.length() < 16) {
            throw new IllegalStateException("platform.geo.auth.token 长度过短（建议 ≥16），拒绝启动");
        }
        boolean onlineLike = false;
        for (String p : environment.getActiveProfiles()) {
            if ("online".equalsIgnoreCase(p) || "prod".equalsIgnoreCase(p)) {
                onlineLike = true;
                break;
            }
        }
        if (onlineLike) {
            log.info("geo internal auth enabled for online/prod profile");
        }
    }
}

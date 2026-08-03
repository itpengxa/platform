package com.caopan.platform.config;

import com.caopan.platform.geo.config.GeoAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 管理端启动门禁（GEO-002）。
 * <p>管理鉴权以账号密码登录为准；online 若仍配置了弱默认 admin-secret 仅警告。</p>
 */
@Component
public class GeoAdminStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoAdminStartupGuard.class);

    private final Environment environment;
    private final GeoAdminProperties adminProperties;

    public GeoAdminStartupGuard(Environment environment, GeoAdminProperties adminProperties) {
        this.environment = environment;
        this.adminProperties = adminProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminProperties.enabled()) {
            log.warn("platform.geo.admin.enabled=false — 管理端未启用");
            return;
        }
        log.info("geo admin auth mode: username/password session (X-Admin-Token)");
        String secret = adminProperties.normalizedSecret();
        if (StringUtils.hasText(secret)) {
            if (isOnlineLike() && isWeakAdminSecret(secret)) {
                log.warn("platform.geo.admin.secret 为弱默认值，建议清空并仅使用管理员登录");
            } else {
                log.info("legacy X-Admin-Secret still accepted for compatibility");
            }
        }
        String bp = adminProperties.bootstrapPassword();
        if ("admin".equals(bp) || "admin123".equals(bp)) {
            log.warn("bootstrap admin password is weak default — please change after first login");
        }
    }

    private boolean isOnlineLike() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles == null) {
            return false;
        }
        for (String p : profiles) {
            if ("online".equalsIgnoreCase(p) || "prod".equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeakAdminSecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            return true;
        }
        String s = secret.trim().toLowerCase();
        return "local-dev-admin-secret".equals(s)
                || "changeme".equals(s)
                || "secret".equals(s)
                || "password".equals(s);
    }
}

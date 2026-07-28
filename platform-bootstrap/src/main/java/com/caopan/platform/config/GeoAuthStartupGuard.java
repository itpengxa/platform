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
 * 鉴权启动门禁（GEO-001 / platform-bootstrap）。
 * <p>online/prod：禁止 {@code platform.geo.auth.enabled=false}；
 * 且必须配置非空 {@code platform.geo.auth.issue-secret}，避免签发口裸奔。</p>
 */
@Component
public class GeoAuthStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoAuthStartupGuard.class);

    private final Environment environment;
    /** 鉴权独立开关 */
    private final boolean authEnabled;
    /** 签发密钥 */
    private final String issueSecret;

    /**
     * @param environment  用于识别 active profiles（test/online/prod）
     * @param authEnabled  {@code platform.geo.auth.enabled}
     * @param issueSecret  {@code platform.geo.auth.issue-secret}
     */
    public GeoAuthStartupGuard(
            Environment environment,
            @Value("${platform.geo.auth.enabled:false}") boolean authEnabled,
            @Value("${platform.geo.auth.issue-secret:}") String issueSecret) {
        this.environment = environment;
        this.authEnabled = authEnabled;
        this.issueSecret = issueSecret == null ? "" : issueSecret.trim();
    }

    /**
     * 启动后校验：online/prod 必须开启鉴权，且配置 Issue Secret。
     *
     * @param args 启动参数
     * @throws IllegalStateException online/prod 配置不合规
     */
    @Override
    public void run(ApplicationArguments args) {
        boolean onlineLike = false;
        String[] profiles = environment.getActiveProfiles();
        if (profiles != null) {
            for (String p : profiles) {
                if ("online".equalsIgnoreCase(p) || "prod".equalsIgnoreCase(p)) {
                    onlineLike = true;
                    break;
                }
            }
        }
        if (!authEnabled) {
            if (onlineLike) {
                throw new IllegalStateException(
                        "online/prod 环境禁止 platform.geo.auth.enabled=false，请开启鉴权并通过 /api/platform/v1/auth/token/issue 签发 Token");
            }
            log.warn("platform.geo.auth.enabled=false — geo API 未启用 Token 鉴权（仅适用于内网 test）");
            return;
        }
        if (onlineLike && !StringUtils.hasText(issueSecret)) {
            throw new IllegalStateException(
                    "online/prod 必须配置 platform.geo.auth.issue-secret，并通过 Header X-Platform-Issue-Secret 签发 Token");
        }
        if (!StringUtils.hasText(issueSecret)) {
            log.warn("platform.geo.auth.issue-secret 未配置 — token/issue 无签发密钥校验（仅建议本地 test）");
        } else {
            log.info("geo token issue-secret enabled");
        }
        if (onlineLike) {
            log.info("geo DB token auth enabled for online/prod profile");
        } else {
            log.info("geo DB token auth enabled");
        }
    }
}

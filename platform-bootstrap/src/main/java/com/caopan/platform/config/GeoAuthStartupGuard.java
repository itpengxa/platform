package com.caopan.platform.config;

import com.caopan.platform.geo.config.GeoAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 鉴权启动门禁（GEO-001 / platform-bootstrap）。
 * <p>online/prod：禁止 {@code platform.geo.auth.enabled=false}；
 * 且必须配置非空 {@code platform.geo.auth.issue-secret}，避免签发口裸奔。</p>
 */
@Component
public class GeoAuthStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoAuthStartupGuard.class);

    private final Environment environment;
    private final GeoAuthProperties authProperties;

    public GeoAuthStartupGuard(Environment environment, GeoAuthProperties authProperties) {
        this.environment = environment;
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean onlineLike = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "online".equalsIgnoreCase(p) || "prod".equalsIgnoreCase(p));
        String issueSecret = authProperties.normalizedIssueSecret();

        if (!authProperties.enabled()) {
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
        log.info(onlineLike
                ? "geo DB token auth enabled for online/prod profile"
                : "geo DB token auth enabled");
    }
}

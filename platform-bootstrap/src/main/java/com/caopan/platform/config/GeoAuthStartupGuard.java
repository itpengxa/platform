package com.caopan.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 鉴权启动门禁（GEO-001 / platform-bootstrap）。
 * <p>online/prod 禁止 {@code platform.geo.auth.enabled=false}。
 * Token 已改为 DB 签发（{@code /api/platform/v1/auth/token/issue}），不再校验 yml 静态 token。</p>
 */
@Component
public class GeoAuthStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoAuthStartupGuard.class);

    private final Environment environment;
    /** 鉴权独立开关 */
    private final boolean authEnabled;

    /**
     * @param environment 用于识别 active profiles（test/online/prod）
     * @param authEnabled {@code platform.geo.auth.enabled}
     */
    public GeoAuthStartupGuard(
            Environment environment,
            @Value("${platform.geo.auth.enabled:false}") boolean authEnabled) {
        this.environment = environment;
        this.authEnabled = authEnabled;
    }

    /**
     * 启动后校验：online/prod 必须开启鉴权。
     *
     * @param args 启动参数
     * @throws IllegalStateException online/prod 且 auth.enabled=false
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
        if (onlineLike) {
            log.info("geo DB token auth enabled for online/prod profile");
        } else {
            log.info("geo DB token auth enabled");
        }
    }
}

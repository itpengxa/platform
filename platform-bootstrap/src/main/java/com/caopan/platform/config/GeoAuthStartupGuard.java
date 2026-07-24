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
 * <p>应用启动时检查：若开启 {@code platform.geo.auth.enabled}，则 Token 必须非空且长度足够，
 * 避免空口令误开放。online/prod profile 下开启鉴权时打确认日志；
 * test 可关闭鉴权仅用于内网联调。</p>
 */
@Component
public class GeoAuthStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoAuthStartupGuard.class);

    private final Environment environment;
    private final boolean authEnabled;
    private final String authToken;

    /**
     * 注入依赖构造。
     *
     * @param environment 用于识别 active profiles（test/online/prod）
     * @param authEnabled 是否启用内部鉴权
     * @param authToken   配置的 Token
     */
    public GeoAuthStartupGuard(
            Environment environment,
            @Value("${platform.geo.auth.enabled:false}") boolean authEnabled,
            @Value("${platform.geo.auth.token:}") String authToken) {
        this.environment = environment;
        this.authEnabled = authEnabled;
        this.authToken = authToken == null ? "" : authToken.trim();
    }

    /**
     * 启动后校验鉴权配置；Token 非法时抛 {@link IllegalStateException} 阻止启动。
     *
     * @param args 启动参数
     */
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

package com.caopan.platform.config;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.i18n.ErrorMessages;
import com.caopan.platform.geo.admin.AdminAuthController;
import com.caopan.platform.geo.admin.access.AdminAuthService;
import com.caopan.platform.geo.config.runtime.EffectiveAdminSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理端鉴权过滤器（GEO-002）。
 * <p>管理 API 优先校验会话 Token（{@code X-Admin-Token} / Bearer）；
 * 兼容过渡期仍接受 {@code X-Admin-Secret}。登录接口与静态资源放行。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    /** @deprecated 兼容旧密钥头 */
    public static final String HEADER_ADMIN_SECRET = "X-Admin-Secret";
    public static final String HEADER_ADMIN_TOKEN = AdminAuthController.HEADER_ADMIN_TOKEN;

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final EffectiveAdminSettings adminSettings;
    private final AdminAuthService adminAuthService;

    public AdminAuthFilter(
            ObjectMapper objectMapper,
            MessageSource messageSource,
            EffectiveAdminSettings adminSettings,
            AdminAuthService adminAuthService) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.adminSettings = adminSettings;
        this.adminAuthService = adminAuthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        String prefix = adminSettings.normalizedPathPrefix();
        // 无论 enabled 与否都进入过滤器：enabled=false 时对管理 API 闭包拒绝，避免“关开关=裸奔”
        return !uri.equals(prefix) && !uri.startsWith(prefix + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!adminSettings.enabled()) {
            if (isStaticAssetGet(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            log.warn("admin disabled, reject uri={}, method={}", request.getRequestURI(), request.getMethod());
            writeUnauthorized(response);
            return;
        }
        if (isPublicAdminPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!(isAdminApi(request.getRequestURI()) || !"GET".equalsIgnoreCase(request.getMethod()))) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = AdminAuthController.extractToken(request);
        if (StringUtils.hasText(token)) {
            try {
                adminAuthService.requireSession(token);
                filterChain.doFilter(request, response);
                return;
            } catch (BizException e) {
                log.warn("admin token unauthorized, uri={}", request.getRequestURI());
                writeUnauthorized(response);
                return;
            }
        }

        // 兼容旧 Secret
        String secret = adminSettings.normalizedSecret();
        String provided = request.getHeader(HEADER_ADMIN_SECRET);
        if (StringUtils.hasText(secret) && secret.equals(provided)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("admin unauthorized, uri={}, method={}", request.getRequestURI(), request.getMethod());
        writeUnauthorized(response);
    }

    static boolean isPublicAdminPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        if ("POST".equalsIgnoreCase(request.getMethod())
                && (uri.endsWith("/admin/platform/v1/auth/login")
                || uri.contains("/admin/platform/v1/auth/login"))) {
            return true;
        }
        return isStaticAssetGet(request);
    }

    static boolean isStaticAssetGet(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        if (uri.endsWith(".html") || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".ico")) {
            return true;
        }
        return uri.equals("/admin") || uri.equals("/admin/");
    }

    /** @deprecated 使用 {@link #isStaticAssetGet} */
    static boolean isStaticHtmlGet(HttpServletRequest request) {
        return isStaticAssetGet(request);
    }

    static boolean isAdminApi(String uri) {
        return uri != null && uri.contains("/v1/");
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = ErrorMessages.resolve(messageSource, ErrorCode.ADMIN_UNAUTHORIZED);
        Result<Void> body = Result.fail(ErrorCode.ADMIN_UNAUTHORIZED.getCode(), message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}

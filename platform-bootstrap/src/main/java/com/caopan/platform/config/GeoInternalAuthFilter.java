package com.caopan.platform.config;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.i18n.ErrorMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部 Token 鉴权过滤器（GEO-001 / platform-bootstrap，W5）。
 * <p>Order 低于 IP 限流，保证「限流在鉴权前」。仅当 {@code platform.geo.auth.enabled=true} 时生效；
 * 校验 {@code X-Platform-Token} 或 {@code Authorization: Bearer ...}。
 * test 环境默认关闭，online/prod 建议开启并由 {@link GeoAuthStartupGuard} 校验 Token 非空。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GeoInternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GeoInternalAuthFilter.class);
    private static final String GEO_PREFIX = "/api/geo/v1";
    private static final String HEADER_TOKEN = "X-Platform-Token";

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final boolean enabled;
    private final String expectedToken;

    /**
     * 注入依赖构造。
     *
     * @param objectMapper  JSON 写出 401 响应
     * @param messageSource 未授权文案国际化
     * @param enabled       是否启用鉴权
     * @param expectedToken 期望 Token（启用时须非空）
     */
    public GeoInternalAuthFilter(
            ObjectMapper objectMapper,
            MessageSource messageSource,
            @Value("${platform.geo.auth.enabled:false}") boolean enabled,
            @Value("${platform.geo.auth.token:}") String expectedToken) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.enabled = enabled;
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(GEO_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!StringUtils.hasText(expectedToken)) {
            log.error("geo auth enabled but platform.geo.auth.token is empty");
            writeUnauthorized(request, response);
            return;
        }
        String token = resolveToken(request);
        if (!constantTimeEquals(expectedToken, token)) {
            log.warn("geo auth rejected, ip={}, uri={}", request.getRemoteAddr(), request.getRequestURI());
            writeUnauthorized(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_TOKEN);
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (StringUtils.hasText(auth) && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = ErrorMessages.resolve(
                messageSource, ErrorCode.UNAUTHORIZED, GlobalExceptionHandler.resolveLocale(request));
        Result<Void> body = Result.fail(ErrorCode.UNAUTHORIZED.getCode(), message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}

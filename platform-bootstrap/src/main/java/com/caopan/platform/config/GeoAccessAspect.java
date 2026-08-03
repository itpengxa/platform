package com.caopan.platform.config;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.auth.CallerContextHolder;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AccessTokenService;
import com.caopan.platform.geo.access.ApiAccessStatRecorder;
import com.caopan.platform.geo.config.runtime.EffectiveAccessLogSettings;
import com.caopan.platform.geo.config.runtime.EffectiveAuthSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Geo Controller 访问切面：鉴权 + 虚拟线程异步入参/异常/统计。
 */
@Aspect
@Component
@Order(0)
public class GeoAccessAspect {

    private static final Logger log = LoggerFactory.getLogger(GeoAccessAspect.class);
    private static final String HEADER_TOKEN = "X-Platform-Token";

    private final AccessTokenService accessTokenService;
    private final ApiAccessStatRecorder statRecorder;
    private final ObjectMapper objectMapper;
    private final EffectiveAuthSettings authSettings;
    private final EffectiveAccessLogSettings accessLogSettings;

    public GeoAccessAspect(
            AccessTokenService accessTokenService,
            ApiAccessStatRecorder statRecorder,
            ObjectMapper objectMapper,
            EffectiveAuthSettings authSettings,
            EffectiveAccessLogSettings accessLogSettings) {
        this.accessTokenService = accessTokenService;
        this.statRecorder = statRecorder;
        this.objectMapper = objectMapper;
        this.authSettings = authSettings;
        this.accessLogSettings = accessLogSettings;
    }

    @Around("within(com.caopan.platform.geo.controller..*) && @within(org.springframework.web.bind.annotation.RestController)")
    public Object aroundGeoController(ProceedingJoinPoint pjp) throws Throwable {
        long startNs = System.nanoTime();
        LocalDateTime calledAt = LocalDateTime.now();
        HttpServletRequest request = currentRequest();
        String apiKey = resolveApiKey(request, pjp);
        String paramsSnapshot = buildParamsSnapshot(pjp);
        CallerContext caller = null;
        boolean success = false;
        Throwable error = null;

        try {
            caller = authSettings.enabled()
                    ? accessTokenService.parse(resolveToken(request))
                    : CallerContext.anonymous();
            CallerContextHolder.set(caller);
            Object result = pjp.proceed();
            success = true;
            return result;
        } catch (Throwable t) {
            error = t;
            success = false;
            if (accessLogSettings.exceptionEnabled()) {
                final CallerContext c = caller;
                final String api = apiKey;
                Thread.startVirtualThread(() -> logException(c, api, t));
            }
            throw t;
        } finally {
            CallerContextHolder.clear();
            final CallerContext c = caller == null ? CallerContext.anonymous() : caller;
            final boolean ok = success;
            final Throwable err = error;
            final int costMs = (int) Math.min((System.nanoTime() - startNs) / 1_000_000L, Integer.MAX_VALUE);
            final String params = paramsSnapshot;
            final String api = apiKey;
            final LocalDateTime at = calledAt;
            final boolean argsEnabled = accessLogSettings.argsEnabled();
            Thread.startVirtualThread(() -> {
                if (argsEnabled) {
                    log.info("geo api args, client={}, api={}, params={}", c.clientCode(), api, params);
                }
                statRecorder.record(
                        c.clientCode(),
                        at,
                        api,
                        params,
                        ok,
                        err == null ? null : err.getClass().getSimpleName(),
                        costMs);
            });
        }
    }

    private static void logException(CallerContext caller, String apiKey, Throwable t) {
        String client = caller == null ? "anonymous" : caller.clientCode();
        switch (t) {
            case BizException biz when biz.getCode() == ErrorCode.UNAUTHORIZED.getCode() ->
                    log.warn("geo api unauthorized, client={}, api={}", client, apiKey);
            case BizException biz ->
                    log.warn("geo api biz error, client={}, api={}, type={}, msg={}",
                            client, apiKey, biz.getClass().getSimpleName(), biz.getMessage());
            default -> log.error("geo api exception, client={}, api={}", client, apiKey, t);
        }
    }

    private String buildParamsSnapshot(ProceedingJoinPoint pjp) {
        int maxLen = accessLogSettings.paramsMaxLength();
        try {
            Object[] args = pjp.getArgs();
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            String[] names = sig.getParameterNames();
            String joined = IntStream.range(0, args.length)
                    .filter(i -> !(args[i] instanceof HttpServletRequest
                            || args[i] instanceof HttpServletResponse))
                    .mapToObj(i -> {
                        String name = names != null && i < names.length ? names[i] : ("arg" + i);
                        String value;
                        try {
                            value = objectMapper.writeValueAsString(args[i]);
                        } catch (Exception e) {
                            value = String.valueOf(args[i]);
                        }
                        return name + "=" + value;
                    })
                    .collect(Collectors.joining(", "));
            return joined.length() > maxLen ? joined.substring(0, maxLen) : joined;
        } catch (Exception e) {
            return "<unserializable>";
        }
    }

    private static String resolveApiKey(HttpServletRequest request, ProceedingJoinPoint pjp) {
        if (request != null) {
            return request.getMethod() + " " + request.getRequestURI();
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return "INVOKE " + sig.getDeclaringTypeName() + "#" + sig.getMethod().getName();
    }

    private static String resolveToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
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

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}

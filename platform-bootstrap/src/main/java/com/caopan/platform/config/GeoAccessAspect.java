package com.caopan.platform.config;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.auth.CallerContextHolder;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AccessTokenService;
import com.caopan.platform.geo.access.ApiAccessStatRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Geo Controller 访问切面（GEO-001 / platform-bootstrap）。
 * <p>职责：
 * <ol>
 *   <li>DB Token 解析（{@code platform.geo.auth.enabled=true} 时同步查库，失败抛 UNAUTHORIZED → HTTP 401）</li>
 *   <li>虚拟线程异步打印 Controller 入参（info 日志文件）</li>
 *   <li>虚拟线程异步打印异常（BizException→warn，其它→error 日志文件）</li>
 *   <li>虚拟线程异步写调用统计（应用/时间/接口/参数/成败；BizException 算失败）</li>
 * </ol>
 * 不切 {@code TokenIssueController}（位于 {@code geo.auth}，不在 {@code geo.controller} 切点）。
 * 限流仍由 {@link GeoIpRateLimitFilter} 独立开关控制。</p>
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
    /** 鉴权独立开关 */
    private final boolean authEnabled;
    /** 是否异步打印入参 */
    private final boolean argsLogEnabled;
    /** 是否异步打印异常 */
    private final boolean exceptionLogEnabled;
    /** 入参快照最大长度 */
    private final int paramsMaxLength;

    /**
     * @param accessTokenService    Token 解析服务
     * @param statRecorder          调用统计落库
     * @param objectMapper          入参序列化
     * @param authEnabled           {@code platform.geo.auth.enabled}
     * @param argsLogEnabled        {@code platform.geo.access-log.args-enabled}
     * @param exceptionLogEnabled   {@code platform.geo.access-log.exception-enabled}
     * @param paramsMaxLength       {@code platform.geo.access-log.params-max-length}
     */
    public GeoAccessAspect(
            AccessTokenService accessTokenService,
            ApiAccessStatRecorder statRecorder,
            ObjectMapper objectMapper,
            @Value("${platform.geo.auth.enabled:false}") boolean authEnabled,
            @Value("${platform.geo.access-log.args-enabled:true}") boolean argsLogEnabled,
            @Value("${platform.geo.access-log.exception-enabled:true}") boolean exceptionLogEnabled,
            @Value("${platform.geo.access-log.params-max-length:2048}") int paramsMaxLength) {
        this.accessTokenService = accessTokenService;
        this.statRecorder = statRecorder;
        this.objectMapper = objectMapper;
        this.authEnabled = authEnabled;
        this.argsLogEnabled = argsLogEnabled;
        this.exceptionLogEnabled = exceptionLogEnabled;
        this.paramsMaxLength = Math.max(paramsMaxLength, 64);
    }

    /**
     * 环绕 geo.controller 下全部 RestController 方法。
     *
     * @param pjp 连接点
     * @return 业务方法返回值
     * @throws Throwable 业务或鉴权异常原样抛出（由 GlobalExceptionHandler 转 Result）
     */
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
            if (authEnabled) {
                caller = accessTokenService.parse(resolveToken(request));
            } else {
                caller = CallerContext.anonymous();
            }
            CallerContextHolder.set(caller);
            Object result = pjp.proceed();
            success = true;
            return result;
        } catch (Throwable t) {
            error = t;
            success = false;
            if (exceptionLogEnabled) {
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
            Thread.startVirtualThread(() -> {
                if (argsLogEnabled) {
                    log.info("geo api args, client={}, api={}, params={}", c.getClientCode(), api, params);
                }
                statRecorder.record(
                        c.getClientCode(),
                        at,
                        api,
                        params,
                        ok,
                        err == null ? null : err.getClass().getSimpleName(),
                        costMs);
            });
        }
    }

    /**
     * 按异常类型选择 warn / error 日志（走分文件 appender）。
     */
    private static void logException(CallerContext caller, String apiKey, Throwable t) {
        String client = caller == null ? "anonymous" : caller.getClientCode();
        if (t instanceof BizException biz && biz.getCode() == ErrorCode.UNAUTHORIZED.getCode()) {
            log.warn("geo api unauthorized, client={}, api={}", client, apiKey);
            return;
        }
        if (t instanceof BizException) {
            log.warn("geo api biz error, client={}, api={}, type={}, msg={}",
                    client, apiKey, t.getClass().getSimpleName(), t.getMessage());
            return;
        }
        log.error("geo api exception, client={}, api={}", client, apiKey, t);
    }

    /**
     * 序列化方法入参为短字符串（跳过 Servlet 对象，禁止依赖 Token 明文）。
     */
    private String buildParamsSnapshot(ProceedingJoinPoint pjp) {
        try {
            Object[] args = pjp.getArgs();
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            String[] names = sig.getParameterNames();
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof HttpServletRequest || arg instanceof jakarta.servlet.http.HttpServletResponse) {
                    continue;
                }
                String name = names != null && i < names.length ? names[i] : ("arg" + i);
                String value;
                try {
                    value = objectMapper.writeValueAsString(arg);
                } catch (Exception e) {
                    value = String.valueOf(arg);
                }
                parts.add(name + "=" + value);
            }
            String joined = String.join(", ", parts);
            if (joined.length() > paramsMaxLength) {
                return joined.substring(0, paramsMaxLength);
            }
            return joined;
        } catch (Exception e) {
            return "<unserializable>";
        }
    }

    /**
     * 统计用接口键：优先 {@code METHOD URI}，无 request 时退化为类名#方法名。
     */
    private static String resolveApiKey(HttpServletRequest request, ProceedingJoinPoint pjp) {
        if (request != null) {
            String method = request.getMethod();
            String uri = request.getRequestURI();
            return method + " " + uri;
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return "INVOKE " + sig.getDeclaringTypeName() + "#" + sig.getMethod().getName();
    }

    /**
     * 从 Header 提取 Token：优先 {@code X-Platform-Token}，其次 {@code Authorization: Bearer}。
     *
     * @param request HTTP 请求，可为 null
     * @return 明文 Token 或 null
     */
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

    /** @return 当前请求，非 Web 线程时 null */
    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }
}

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 按客户端 IP 防刷（GEO-001 / platform-bootstrap）。
 * <p>Order 高于 Controller 切面鉴权，保证限流在鉴权之前执行。
 * 覆盖 {@code /api/geo/v1/**} 与 Token 签发 {@code /api/platform/v1/auth/token/issue}。
 * 独立开关 {@code platform.geo.rate-limit.enabled}。
 * 默认接口最短间隔 1s；搜索接口独立桶 1s；树查询 2s。
 * 默认不信任 X-Forwarded-For（防伪造）；仅网关剥离客户端 XFF 后可开启 trust-forwarded-headers。
 * Redis 不可用时：默认降级本地限流；online 建议开启 fail-closed 拒绝请求。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GeoIpRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GeoIpRateLimitFilter.class);

    private static final String GEO_PREFIX = "/api/geo/v1";
    private static final String TOKEN_ISSUE_PATH = "/api/platform/v1/auth/token/issue";
    private static final String TREE_PATH = "/api/geo/v1/regions/tree";
    private static final String SEARCH_PATH = "/api/geo/v1/regions/search";
    private static final String REDIS_KEY_PREFIX = "platform:geo:rl:";
    private static final Pattern IP_SAFE = Pattern.compile("^[0-9a-fA-F.:]{3,64}$");

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;
    private final boolean enabled;
    private final boolean trustForwardedHeaders;
    private final boolean failClosed;
    private final long defaultIntervalMs;
    private final long searchIntervalMs;
    private final long treeIntervalMs;

    private final ConcurrentHashMap<String, AtomicLong> localWindow = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupAt = new AtomicLong(0L);

    /**
     * 注入依赖构造。
     *
     * @param objectMapper           JSON 写出限流响应
     * @param messageSource          限流文案国际化
     * @param redisProvider          可选 Redis（分布式限流）
     * @param redisEnabled           是否启用 Redis
     * @param enabled                是否启用本过滤器
     * @param trustForwardedHeaders  是否信任 XFF/X-Real-IP
     * @param failClosed             Redis 不可用时是否直接拒绝
     * @param defaultIntervalMs      默认接口最小间隔毫秒
     * @param searchIntervalMs       搜索接口最小间隔毫秒
     * @param treeIntervalMs         树接口最小间隔毫秒
     */
    public GeoIpRateLimitFilter(
            ObjectMapper objectMapper,
            MessageSource messageSource,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${platform.geo.cache.redis-enabled:true}") boolean redisEnabled,
            @Value("${platform.geo.rate-limit.enabled:true}") boolean enabled,
            @Value("${platform.geo.rate-limit.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
            @Value("${platform.geo.rate-limit.fail-closed:false}") boolean failClosed,
            @Value("${platform.geo.rate-limit.default-interval-ms:1000}") long defaultIntervalMs,
            @Value("${platform.geo.rate-limit.search-interval-ms:1000}") long searchIntervalMs,
            @Value("${platform.geo.rate-limit.tree-interval-ms:2000}") long treeIntervalMs) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.redisEnabled = redisEnabled && this.redisTemplate != null;
        this.enabled = enabled;
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.failClosed = failClosed;
        this.defaultIntervalMs = Math.max(defaultIntervalMs, 1L);
        this.searchIntervalMs = Math.max(searchIntervalMs, 1L);
        this.treeIntervalMs = Math.max(treeIntervalMs, 1L);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        return !uri.startsWith(GEO_PREFIX) && !isPath(uri, TOKEN_ISSUE_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String bucket;
        long intervalMs;
        if (isPath(uri, TOKEN_ISSUE_PATH)) {
            bucket = "issue";
            intervalMs = defaultIntervalMs;
        } else if (isPath(uri, TREE_PATH)) {
            bucket = "tree";
            intervalMs = treeIntervalMs;
        } else if (isPath(uri, SEARCH_PATH)) {
            // 搜索直打 DB、无三级缓存，独立桶按 IP 限流（默认 1qps）
            bucket = "search";
            intervalMs = searchIntervalMs;
        } else {
            bucket = "default";
            intervalMs = defaultIntervalMs;
        }
        String ip = resolveClientIp(request, trustForwardedHeaders);
        String limitKey = ip + ":" + bucket;

        if (!tryAcquire(limitKey, intervalMs)) {
            log.warn("geo rate limited, ip={}, uri={}, bucket={}, intervalMs={}", ip, uri, bucket, intervalMs);
            writeLimited(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isPath(String uri, String canonical) {
        if (uri == null || canonical == null) {
            return false;
        }
        if (canonical.equals(uri)) {
            return true;
        }
        return (canonical + "/").equals(uri);
    }

    private boolean tryAcquire(String limitKey, long intervalMs) {
        if (redisEnabled) {
            try {
                return tryAcquireRedis(REDIS_KEY_PREFIX + limitKey, Math.max(intervalMs, 1L));
            } catch (Exception e) {
                if (failClosed) {
                    log.warn("redis rate-limit failed, fail-closed reject, key={}, err={}", limitKey, e.toString());
                    return false;
                }
                log.warn("redis rate-limit failed, fallback local, key={}, err={}", limitKey, e.toString());
            }
        } else if (failClosed) {
            // 期望 Redis 限流但未启用 → 拒绝，避免多实例放大
            log.warn("redis rate-limit unavailable, fail-closed reject, key={}", limitKey);
            return false;
        }
        return tryAcquireLocal(limitKey, intervalMs);
    }

    /**
     * Redis SET NX + PX（毫秒）。若历史脏 key 无 TTL（PTTL=-1）会永不过期，主动删除后重试一次。
     */
    private boolean tryAcquireRedis(String redisKey, long ttlMs) {
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", ttlMs, TimeUnit.MILLISECONDS);
        if (Boolean.TRUE.equals(ok)) {
            // 个别客户端/版本偶发未带过期：强制补 PX，避免限流锁死
            Long pttl = redisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);
            if (pttl != null && pttl == -1L) {
                redisTemplate.expire(redisKey, ttlMs, TimeUnit.MILLISECONDS);
            }
            return true;
        }
        Long pttl = redisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);
        // -2 不存在（并发下刚过期）→ 允许本请求重试占坑；-1 无过期 → 删掉重试
        if (pttl != null && pttl == -1L) {
            log.warn("rate-limit dirty key without TTL, delete and retry, key={}", redisKey);
            redisTemplate.delete(redisKey);
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", ttlMs, TimeUnit.MILLISECONDS));
        }
        return false;
    }

    private boolean tryAcquireLocal(String limitKey, long intervalMs) {
        long now = System.currentTimeMillis();
        maybeCleanupLocal(now);
        AtomicLong last = localWindow.computeIfAbsent(limitKey, k -> new AtomicLong(0L));
        while (true) {
            long prev = last.get();
            if (prev > 0 && now - prev < intervalMs) {
                return false;
            }
            if (last.compareAndSet(prev, now)) {
                return true;
            }
        }
    }

    private void maybeCleanupLocal(long now) {
        long prev = lastCleanupAt.get();
        if (now - prev < 60_000L) {
            return;
        }
        if (!lastCleanupAt.compareAndSet(prev, now)) {
            return;
        }
        long expireBefore = now - Math.max(defaultIntervalMs, treeIntervalMs) * 10;
        Iterator<Map.Entry<String, AtomicLong>> it = localWindow.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AtomicLong> e = it.next();
            if (e.getValue().get() < expireBefore) {
                it.remove();
            }
        }
    }

    /**
     * 解析客户端 IP。
     *
     * @param request         HTTP 请求
     * @param trustForwarded  仅当反向代理已改写/剥离客户端伪造头时为 true
     * @return 安全校验后的 IP，无法识别时返回 {@code unknown}
     */
    static String resolveClientIp(HttpServletRequest request, boolean trustForwarded) {
        if (trustForwarded) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (isSafeIp(first)) {
                    return first;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank() && isSafeIp(realIp.trim())) {
                return realIp.trim();
            }
        }
        String remote = request.getRemoteAddr();
        if (remote != null && !remote.isBlank() && isSafeIp(remote)) {
            return remote;
        }
        return "unknown";
    }

    private static boolean isSafeIp(String ip) {
        return ip != null && IP_SAFE.matcher(ip).matches();
    }

    private void writeLimited(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = ErrorMessages.resolve(messageSource, ErrorCode.RATE_LIMITED);
        Result<Void> body = Result.fail(ErrorCode.RATE_LIMITED.getCode(), message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}

package com.caopan.platform.geo.access;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.config.GeoAuthProperties;
import com.caopan.platform.geo.entity.PlatformAccessClient;
import com.caopan.platform.geo.entity.PlatformAccessToken;
import com.caopan.platform.geo.mapper.PlatformAccessClientMapper;
import com.caopan.platform.geo.mapper.PlatformAccessTokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 长效 Token 签发与查库解析（GEO-001 / platform-geo-service）。
 * <p>多实例：启用 Redis 时 Issue 使用分布式锁（锁仅覆盖事务段，由 {@link TransactionTemplate} 开启）；
 * 解析以 <b>DB 为权威</b>：库内无效则 401 并清理 Redis；库内有效则回填/刷新 {@code valid:{hash}}（带 TTL）。
 * Redis 未启用时退化为仅 DB。</p>
 */
@Service
public class AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);
    private static final Pattern CLIENT_CODE = Pattern.compile("^[A-Za-z0-9_\\-]{2,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformAccessClientMapper clientMapper;
    private final PlatformAccessTokenMapper tokenMapper;
    private final AccessTokenRedisSupport redisSupport;
    private final TransactionTemplate transactionTemplate;

    public AccessTokenService(
            PlatformAccessClientMapper clientMapper,
            PlatformAccessTokenMapper tokenMapper,
            ObjectProvider<StringRedisTemplate> redisProvider,
            PlatformTransactionManager transactionManager,
            GeoCacheProperties cacheProperties,
            GeoAuthProperties authProperties) {
        this.clientMapper = clientMapper;
        this.tokenMapper = tokenMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        boolean sync = cacheProperties.redisEnabled()
                && authProperties.redisTokenSyncEnabled()
                && redis != null;
        if (sync) {
            this.redisSupport = new AccessTokenRedisSupport(
                    redis,
                    authProperties.issueLockKeyPrefix(),
                    authProperties.validKeyPrefix(),
                    authProperties.issueLockSeconds(),
                    authProperties.issueLockRetryTimes(),
                    authProperties.issueLockRetryMs(),
                    Duration.ofDays(Math.max(authProperties.validTtlDays(), 1L)));
        } else {
            this.redisSupport = null;
            if (authProperties.redisTokenSyncEnabled() && cacheProperties.redisEnabled()) {
                log.warn("platform.geo.auth.redis-token-sync-enabled but Redis unavailable — token sync disabled");
            }
        }
    }

    /**
     * 签发（或换新）Token；明文仅在本方法返回值中出现一次。
     * <p>先抢 Redis 锁，再在锁内开启短事务写库，避免锁 TTL 短于事务持有时间。</p>
     */
    public IssuedToken issue(String clientCode, String clientName) {
        String code = normalizeClientCode(clientCode);
        String lockToken = null;
        if (redisSupport != null) {
            lockToken = redisSupport.tryAcquireIssueLock(code);
            if (lockToken == null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR);
            }
        }
        try {
            return transactionTemplate.execute(status -> issueUnderLock(code, clientName));
        } finally {
            if (redisSupport != null) {
                redisSupport.releaseIssueLock(code, lockToken);
            }
        }
    }

    private IssuedToken issueUnderLock(String code, String clientName) {
        PlatformAccessClient client = clientMapper.findByCode(code);
        LocalDateTime now = LocalDateTime.now();
        if (client == null) {
            client = new PlatformAccessClient();
            client.setClientCode(code);
            client.setClientName(StringUtils.hasText(clientName) ? clientName.trim() : code);
            client.setStatus(1);
            client.setCreatedAt(now);
            client.setUpdatedAt(now);
            clientMapper.insert(client);
        } else {
            if (!Objects.equals(client.getStatus(), 1)) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            if (StringUtils.hasText(clientName) && !clientName.trim().equals(client.getClientName())) {
                client.setClientName(clientName.trim());
                client.setUpdatedAt(now);
                clientMapper.updateById(client);
            }
        }

        List<String> oldHashes = tokenMapper.listActiveTokenHashesByClientId(client.getId());
        if (oldHashes == null) {
            oldHashes = List.of();
        }
        tokenMapper.revokeActiveByClientId(client.getId());

        String plain = generatePlainToken();
        String hash = sha256Hex(plain);
        PlatformAccessToken row = new PlatformAccessToken();
        row.setClientId(client.getId());
        row.setTokenHash(hash);
        row.setTokenPrefix(plain.substring(0, Math.min(8, plain.length())));
        row.setStatus(1);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        tokenMapper.insert(row);

        if (redisSupport != null) {
            redisSupport.revokeValid(oldHashes);
            redisSupport.markValid(hash);
        }

        log.info("access token issued, clientCode={}, tokenId={}, prefix={}",
                code, row.getId(), row.getTokenPrefix());
        return new IssuedToken(code, plain, row.getTokenPrefix());
    }

    /**
     * 解析：以 DB 为权威；库内无效 → 401 并清理 Redis；库内有效 → 回填/刷新 Redis valid（带 TTL）。
     */
    public CallerContext parse(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String hash = sha256Hex(rawToken.trim());

        TokenCallerRow row = tokenMapper.findActiveCallerByHash(hash);
        if (row == null || !StringUtils.hasText(row.clientCode())) {
            if (redisSupport != null) {
                redisSupport.revokeValid(Collections.singletonList(hash));
            }
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        if (redisSupport != null) {
            redisSupport.markValid(hash);
        }
        return new CallerContext(row.clientId(), row.clientCode(), row.tokenId());
    }

    private static String normalizeClientCode(String clientCode) {
        if (!StringUtils.hasText(clientCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = clientCode.trim();
        if (!CLIENT_CODE.matcher(code).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return code;
    }

    private static String generatePlainToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 签发结果（明文 token 仅此一次出现）。 */
    public record IssuedToken(String clientCode, String token, String tokenPrefix) {
        public String getClientCode() {
            return clientCode;
        }

        public String getToken() {
            return token;
        }

        public String getTokenPrefix() {
            return tokenPrefix;
        }
    }
}

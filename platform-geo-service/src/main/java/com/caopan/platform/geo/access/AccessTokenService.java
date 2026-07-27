package com.caopan.platform.geo.access;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.entity.PlatformAccessClient;
import com.caopan.platform.geo.entity.PlatformAccessToken;
import com.caopan.platform.geo.mapper.PlatformAccessClientMapper;
import com.caopan.platform.geo.mapper.PlatformAccessTokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 长效 Token 签发与查库解析（GEO-001 / platform-geo-service）。
 * <p>多实例：启用 Redis 时 Issue 使用分布式锁；解析先查 {@code valid:{hash}} 再查库。
 * Redis 未启用时退化为仅 DB（无跨节点吊销同步）。</p>
 */
@Service
public class AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);
    private static final Pattern CLIENT_CODE = Pattern.compile("^[A-Za-z0-9_\\-]{2,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformAccessClientMapper clientMapper;
    private final PlatformAccessTokenMapper tokenMapper;
    private final AccessTokenRedisSupport redisSupport;

    /**
     * @param clientMapper            接入方 Mapper
     * @param tokenMapper             Token Mapper
     * @param redisProvider           可选 Redis
     * @param cacheRedisEnabled       {@code platform.geo.cache.redis-enabled}
     * @param redisTokenSyncEnabled   {@code platform.geo.auth.redis-token-sync-enabled}
     * @param issueLockPrefix         Issue 锁 key 前缀
     * @param validKeyPrefix          valid 标记 key 前缀
     * @param issueLockSeconds        锁 TTL 秒
     * @param issueLockRetryTimes     抢锁重试次数
     * @param issueLockRetryMs        抢锁重试间隔毫秒
     */
    public AccessTokenService(
            PlatformAccessClientMapper clientMapper,
            PlatformAccessTokenMapper tokenMapper,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${platform.geo.cache.redis-enabled:true}") boolean cacheRedisEnabled,
            @Value("${platform.geo.auth.redis-token-sync-enabled:true}") boolean redisTokenSyncEnabled,
            @Value("${platform.geo.auth.issue-lock-key-prefix:platform:auth:issue-lock:}") String issueLockPrefix,
            @Value("${platform.geo.auth.valid-key-prefix:platform:auth:valid:}") String validKeyPrefix,
            @Value("${platform.geo.auth.issue-lock-seconds:30}") long issueLockSeconds,
            @Value("${platform.geo.auth.issue-lock-retry-times:8}") int issueLockRetryTimes,
            @Value("${platform.geo.auth.issue-lock-retry-ms:50}") long issueLockRetryMs) {
        this.clientMapper = clientMapper;
        this.tokenMapper = tokenMapper;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (cacheRedisEnabled && redisTokenSyncEnabled && redis != null) {
            this.redisSupport = new AccessTokenRedisSupport(
                    redis, issueLockPrefix, validKeyPrefix, issueLockSeconds, issueLockRetryTimes, issueLockRetryMs);
        } else {
            this.redisSupport = null;
            if (redisTokenSyncEnabled && cacheRedisEnabled) {
                log.warn("platform.geo.auth.redis-token-sync-enabled but Redis unavailable — token sync disabled");
            }
        }
    }

    /**
     * 签发（或换新）Token；明文仅在本方法返回值中出现一次。
     */
    @Transactional
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
            return issueUnderLock(code, clientName);
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
            if (client.getStatus() == null || client.getStatus() != 1) {
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
            oldHashes = Collections.emptyList();
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
     * 解析：有 Redis 时以 {@code valid:{hash}} 为准；未命中则查库，库内仍有效则回填 Redis。
     * 库内无效则 401 并清理 Redis 脏 key。
     */
    public CallerContext parse(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String token = rawToken.trim();
        String hash = sha256Hex(token);

        TokenCallerRow row = tokenMapper.findActiveCallerByHash(hash);
        if (row == null || !StringUtils.hasText(row.getClientCode())) {
            if (redisSupport != null) {
                redisSupport.revokeValid(Collections.singletonList(hash));
            }
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        if (redisSupport != null && !redisSupport.isValidTokenHash(hash)) {
            redisSupport.markValid(hash);
        }
        return new CallerContext(row.getClientId(), row.getClientCode(), row.getTokenId());
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
            byte[] dig = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static final class IssuedToken {
        private final String clientCode;
        private final String token;
        private final String tokenPrefix;

        public IssuedToken(String clientCode, String token, String tokenPrefix) {
            this.clientCode = clientCode;
            this.token = token;
            this.tokenPrefix = tokenPrefix;
        }

        public String getClientCode() { return clientCode; }
        public String getToken() { return token; }
        public String getTokenPrefix() { return tokenPrefix; }
    }
}

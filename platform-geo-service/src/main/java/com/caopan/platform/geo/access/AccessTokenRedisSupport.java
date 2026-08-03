package com.caopan.platform.geo.access;

import com.caopan.platform.geo.config.runtime.EffectiveAuthSettings;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Token 签发分布式锁与 {@code valid:{hash}} 标记（多实例一致）。
 * <p>前缀/TTL 从 {@link EffectiveAuthSettings} 热读取。</p>
 */
final class AccessTokenRedisSupport {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final EffectiveAuthSettings authSettings;

    AccessTokenRedisSupport(StringRedisTemplate redis, EffectiveAuthSettings authSettings) {
        this.redis = redis;
        this.authSettings = authSettings;
    }

    String tryAcquireIssueLock(String clientCode) {
        String lockKey = authSettings.issueLockKeyPrefix() + clientCode;
        String lockToken = UUID.randomUUID().toString();
        long issueLockSeconds = Math.max(authSettings.issueLockSeconds(), 5L);
        int retries = Math.max(authSettings.issueLockRetryTimes(), 1);
        long retryMs = Math.max(authSettings.issueLockRetryMs(), 10L);
        for (int i = 0; i < retries; i++) {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    lockKey, lockToken, Duration.ofSeconds(issueLockSeconds));
            if (Boolean.TRUE.equals(ok)) {
                return lockToken;
            }
            sleepQuietly(retryMs);
        }
        return null;
    }

    void releaseIssueLock(String clientCode, String lockToken) {
        if (lockToken == null) {
            return;
        }
        String lockKey = authSettings.issueLockKeyPrefix() + clientCode;
        redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
    }

    boolean isValidTokenHash(String tokenHash) {
        return Boolean.TRUE.equals(redis.hasKey(validKey(tokenHash)));
    }

    void markValid(String tokenHash) {
        redis.opsForValue().set(validKey(tokenHash), "1", validTtl());
    }

    void revokeValid(List<String> tokenHashes) {
        if (tokenHashes == null || tokenHashes.isEmpty()) {
            return;
        }
        for (String hash : tokenHashes) {
            if (hash != null && !hash.isBlank()) {
                redis.delete(validKey(hash));
            }
        }
    }

    private Duration validTtl() {
        return Duration.ofDays(Math.max(authSettings.validTtlDays(), 1L));
    }

    private String validKey(String tokenHash) {
        return authSettings.validKeyPrefix() + tokenHash;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

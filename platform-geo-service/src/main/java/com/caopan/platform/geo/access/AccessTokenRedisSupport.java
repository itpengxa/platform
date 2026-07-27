package com.caopan.platform.geo.access;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Token 签发分布式锁与 {@code valid:{hash}} 标记（多实例一致）。
 */
final class AccessTokenRedisSupport {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String issueLockPrefix;
    private final String validPrefix;
    private final long issueLockSeconds;
    private final int issueLockRetryTimes;
    private final long issueLockRetryMs;

    AccessTokenRedisSupport(
            StringRedisTemplate redis,
            String issueLockPrefix,
            String validPrefix,
            long issueLockSeconds,
            int issueLockRetryTimes,
            long issueLockRetryMs) {
        this.redis = redis;
        this.issueLockPrefix = issueLockPrefix;
        this.validPrefix = validPrefix;
        this.issueLockSeconds = Math.max(issueLockSeconds, 5L);
        this.issueLockRetryTimes = Math.max(issueLockRetryTimes, 1);
        this.issueLockRetryMs = Math.max(issueLockRetryMs, 10L);
    }

    /**
     * 尝试获取 per-clientCode 签发锁。
     *
     * @return 锁 token（释放时传入），失败返回 null
     */
    String tryAcquireIssueLock(String clientCode) {
        String lockKey = issueLockPrefix + clientCode;
        String lockToken = UUID.randomUUID().toString();
        for (int i = 0; i < issueLockRetryTimes; i++) {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    lockKey, lockToken, Duration.ofSeconds(issueLockSeconds));
            if (Boolean.TRUE.equals(ok)) {
                return lockToken;
            }
            sleepQuietly(issueLockRetryMs);
        }
        return null;
    }

    void releaseIssueLock(String clientCode, String lockToken) {
        if (lockToken == null) {
            return;
        }
        String lockKey = issueLockPrefix + clientCode;
        redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
    }

    boolean isValidTokenHash(String tokenHash) {
        return Boolean.TRUE.equals(redis.hasKey(validKey(tokenHash)));
    }

    void markValid(String tokenHash) {
        redis.opsForValue().set(validKey(tokenHash), "1");
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

    private String validKey(String tokenHash) {
        return validPrefix + tokenHash;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

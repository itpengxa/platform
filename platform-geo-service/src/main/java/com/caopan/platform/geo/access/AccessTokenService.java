package com.caopan.platform.geo.access;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.entity.PlatformAccessClient;
import com.caopan.platform.geo.entity.PlatformAccessToken;
import com.caopan.platform.geo.mapper.PlatformAccessClientMapper;
import com.caopan.platform.geo.mapper.PlatformAccessTokenMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 长效 Token 签发与查库解析（GEO-001 / platform-geo-service）。
 * <p>产品口径：无状态长效 Token，无自动过期；同一 {@code clientCode} 再次签发会吊销旧 Token
 *（防泄露后继续滥用）。解析结果可短时缓存（{@code platform.geo.auth.token-cache-ttl-seconds}）。</p>
 */
@Service
public class AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);
    /** clientCode：字母数字下划线中划线，2~64 位 */
    private static final Pattern CLIENT_CODE = Pattern.compile("^[A-Za-z0-9_\\-]{2,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformAccessClientMapper clientMapper;
    private final PlatformAccessTokenMapper tokenMapper;
    /** key=tokenHash → CallerContext */
    private final Cache<String, CallerContext> tokenCache;

    /**
     * 注入 Mapper 与缓存 TTL。
     *
     * @param clientMapper     接入方 Mapper
     * @param tokenMapper      Token Mapper
     * @param cacheTtlSeconds  解析结果本地缓存秒数，至少 1
     */
    public AccessTokenService(
            PlatformAccessClientMapper clientMapper,
            PlatformAccessTokenMapper tokenMapper,
            @Value("${platform.geo.auth.token-cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.clientMapper = clientMapper;
        this.tokenMapper = tokenMapper;
        long ttl = Math.max(cacheTtlSeconds, 1L);
        this.tokenCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(ttl, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 签发（或换新）Token；明文仅在本方法返回值中出现一次。
     * <p>client 不存在则自动创建；存在但停用则抛 {@link ErrorCode#PARAM_INVALID}。
     * 签发前吊销该 client 下全部有效 Token，并清空解析缓存。</p>
     *
     * @param clientCode 接入方编码（必填，2~64）
     * @param clientName 展示名（可选；新建时缺省等于 clientCode）
     * @return 含明文 token 的签发结果
     * @throws BizException 参数非法或 client 已停用
     */
    @Transactional
    public IssuedToken issue(String clientCode, String clientName) {
        String code = normalizeClientCode(clientCode);
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

        tokenMapper.revokeActiveByClientId(client.getId());
        tokenCache.invalidateAll();

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

        log.info("access token issued, clientCode={}, tokenId={}, prefix={}",
                code, row.getId(), row.getTokenPrefix());
        return new IssuedToken(code, plain, row.getTokenPrefix());
    }

    /**
     * 解析请求头中的明文 Token（查库）。
     *
     * @param rawToken {@code X-Platform-Token} 或 Bearer 原文
     * @return 调用方上下文
     * @throws BizException {@link ErrorCode#UNAUTHORIZED} 当空/无效/已吊销
     */
    public CallerContext parse(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String token = rawToken.trim();
        String hash = sha256Hex(token);
        CallerContext cached = tokenCache.getIfPresent(hash);
        if (cached != null) {
            return cached;
        }
        TokenCallerRow row = tokenMapper.findActiveCallerByHash(hash);
        if (row == null || !StringUtils.hasText(row.getClientCode())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        CallerContext ctx = new CallerContext(row.getClientId(), row.getClientCode(), row.getTokenId());
        tokenCache.put(hash, ctx);
        return ctx;
    }

    /**
     * 规范化并校验 clientCode。
     *
     * @param clientCode 原始编码
     * @return trim 后的编码
     * @throws BizException 为空或格式不符
     */
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

    /** @return URL-safe Base64 随机 Token（32 字节熵） */
    private static String generatePlainToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 计算 Token 明文的 SHA-256 十六进制（存库与比对用）。
     *
     * @param plain 明文 Token
     * @return 64 位小写 hex
     */
    static String sha256Hex(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 签发结果：明文仅应出现在 HTTP 响应中，禁止写入 info 日志全文。
     */
    public static final class IssuedToken {
        private final String clientCode;
        private final String token;
        private final String tokenPrefix;

        /**
         * @param clientCode  接入方编码
         * @param token       明文 Token（一次性）
         * @param tokenPrefix 明文前缀
         */
        public IssuedToken(String clientCode, String token, String tokenPrefix) {
            this.clientCode = clientCode;
            this.token = token;
            this.tokenPrefix = tokenPrefix;
        }

        /** @return 接入方编码 */
        public String getClientCode() { return clientCode; }

        /** @return 明文 Token */
        public String getToken() { return token; }

        /** @return 明文前缀 */
        public String getTokenPrefix() { return tokenPrefix; }
    }
}

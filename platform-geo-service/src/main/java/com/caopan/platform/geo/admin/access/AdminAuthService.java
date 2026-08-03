package com.caopan.platform.geo.admin.access;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.config.GeoAdminProperties;
import com.caopan.platform.geo.config.runtime.EffectiveAdminSettings;
import com.caopan.platform.geo.entity.PlatformAdminSession;
import com.caopan.platform.geo.entity.PlatformAdminUser;
import com.caopan.platform.geo.mapper.PlatformAdminSessionMapper;
import com.caopan.platform.geo.mapper.PlatformAdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 管理端账号密码登录 / 会话校验（GEO-002）。
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_\\-]{2,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformAdminUserMapper userMapper;
    private final PlatformAdminSessionMapper sessionMapper;
    private final GeoAdminProperties adminProperties;
    private final EffectiveAdminSettings adminSettings;

    public AdminAuthService(
            PlatformAdminUserMapper userMapper,
            PlatformAdminSessionMapper sessionMapper,
            GeoAdminProperties adminProperties,
            EffectiveAdminSettings adminSettings) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.adminProperties = adminProperties;
        this.adminSettings = adminSettings;
    }

    @Transactional
    public LoginResult login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        PlatformAdminUser user = userMapper.findByUsername(username.trim());
        if (user == null || !Objects.equals(user.getStatus(), 1)) {
            throw new BizException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
        String expect = hashPassword(user.getPasswordSalt(), password);
        if (!expect.equalsIgnoreCase(user.getPasswordHash())) {
            throw new BizException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
        sessionMapper.revokeActiveByUserId(user.getId());
        String plain = generateToken();
        LocalDateTime now = LocalDateTime.now();
        PlatformAdminSession session = new PlatformAdminSession();
        session.setUserId(user.getId());
        session.setTokenHash(sha256Hex(plain));
        session.setTokenPrefix(plain.substring(0, Math.min(8, plain.length())));
        session.setStatus(1);
        session.setExpireAt(now.plusDays(adminSettings.sessionTtlDays()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        log.info("admin login ok, username={}, sessionId={}", user.getUsername(), session.getId());
        return new LoginResult(plain, user.getUsername(), user.getDisplayName(), session.getExpireAt());
    }

    public void logout(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return;
        }
        sessionMapper.revokeByTokenHash(sha256Hex(rawToken.trim()));
    }

    public AdminSessionCaller requireSession(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BizException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
        AdminSessionCaller caller = sessionMapper.findActiveCallerByHash(sha256Hex(rawToken.trim()));
        if (caller == null || !StringUtils.hasText(caller.username())) {
            throw new BizException(ErrorCode.ADMIN_UNAUTHORIZED);
        }
        return caller;
    }

    /** 无管理员时种子默认账号。 */
    @Transactional
    public void ensureBootstrapAdmin() {
        if (userMapper.countAll() > 0) {
            return;
        }
        String username = adminProperties.normalizedBootstrapUsername();
        String password = adminProperties.bootstrapPassword() == null ? "admin" : adminProperties.bootstrapPassword();
        createUserInternal(username, password, "管理员", 1);
        log.warn("seeded bootstrap admin user username={} (please change password)", username);
    }

    @Transactional
    public PlatformAdminUser createUser(String username, String password, String displayName) {
        return createUserInternal(username, password, displayName, 1);
    }

    private PlatformAdminUser createUserInternal(String username, String password, String displayName, int status) {
        if (!StringUtils.hasText(username) || !USERNAME.matcher(username.trim()).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (!StringUtils.hasText(password) || password.length() < 5) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (userMapper.findByUsername(username.trim()) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String salt = randomSalt();
        LocalDateTime now = LocalDateTime.now();
        PlatformAdminUser user = new PlatformAdminUser();
        user.setUsername(username.trim());
        user.setPasswordSalt(salt);
        user.setPasswordHash(hashPassword(salt, password));
        user.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : username.trim());
        user.setStatus(status);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    @Transactional
    public void updatePassword(Long userId, String newPassword) {
        if (userId == null || !StringUtils.hasText(newPassword) || newPassword.length() < 5) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        PlatformAdminUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String salt = randomSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(hashPassword(salt, newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        sessionMapper.revokeActiveByUserId(userId);
    }

    @Transactional
    public void patchStatus(Long userId, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        PlatformAdminUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        if (status == 0) {
            sessionMapper.revokeActiveByUserId(userId);
        }
    }

    static String hashPassword(String salt, String password) {
        return sha256Hex(salt + ":" + password);
    }

    static String sha256Hex(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(String token, String username, String displayName, LocalDateTime expireAt) {
    }
}

package com.caopan.platform.geo.config.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 运行时配置密钥加解密（AES-GCM）。
 * <p>密钥来自 {@code PLATFORM_CONFIG_CRYPTO_KEY}；未配置时 test 可明文，online 写 SECRET 将被拒绝。</p>
 */
@Component
public class ConfigCrypto {

    private static final Logger log = LoggerFactory.getLogger(ConfigCrypto.class);
    private static final String PREFIX = "ENC:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Environment environment;
    private final SecretKey secretKey;
    private final boolean cryptoReady;

    public ConfigCrypto(
            Environment environment,
            @Value("${PLATFORM_CONFIG_CRYPTO_KEY:${platform.config.crypto-key:}}") String cryptoKey) {
        this.environment = environment;
        if (StringUtils.hasText(cryptoKey)) {
            this.secretKey = deriveKey(cryptoKey.trim());
            this.cryptoReady = true;
        } else {
            this.secretKey = null;
            this.cryptoReady = false;
            log.warn("PLATFORM_CONFIG_CRYPTO_KEY not set — SECRET config may be stored plaintext in non-online profiles");
        }
    }

    public boolean isCryptoReady() {
        return cryptoReady;
    }

    public boolean isOnlineProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "online".equalsIgnoreCase(p) || "prod".equalsIgnoreCase(p));
    }

    public String encryptIfNeeded(String plain, boolean secret) {
        if (!secret || plain == null) {
            return plain;
        }
        if (!cryptoReady) {
            if (isOnlineProfile()) {
                throw new IllegalStateException("online profile requires PLATFORM_CONFIG_CRYPTO_KEY to store secrets");
            }
            return plain;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherBytes.length);
            buf.put(iv);
            buf.put(cipherBytes);
            return PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decryptIfNeeded(String stored, boolean secret) {
        if (!secret || stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (!cryptoReady) {
            throw new IllegalStateException("encrypted secret present but PLATFORM_CONFIG_CRYPTO_KEY missing");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(all, 0, GCM_IV_LENGTH);
            byte[] cipherBytes = Arrays.copyOfRange(all, GCM_IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    private static SecretKey deriveKey(String material) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("derive key failed", e);
        }
    }
}

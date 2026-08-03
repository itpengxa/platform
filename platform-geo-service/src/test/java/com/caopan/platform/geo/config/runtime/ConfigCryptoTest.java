package com.caopan.platform.geo.config.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCryptoTest {

    @Test
    void encryptDecrypt_roundTrip() {
        MockEnvironment env = new MockEnvironment();
        ConfigCrypto crypto = new ConfigCrypto(env, "test-crypto-key-123");
        assertTrue(crypto.isCryptoReady());
        String enc = crypto.encryptIfNeeded("super-secret", true);
        assertTrue(enc.startsWith("ENC:"));
        assertNotEquals("super-secret", enc);
        assertEquals("super-secret", crypto.decryptIfNeeded(enc, true));
    }

    @Test
    void nonSecret_passthrough() {
        ConfigCrypto crypto = new ConfigCrypto(new StandardEnvironment(), "k");
        assertEquals("plain", crypto.encryptIfNeeded("plain", false));
        assertEquals("plain", crypto.decryptIfNeeded("plain", false));
    }

    @Test
    void onlineWithoutKey_rejectsSecretEncrypt() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("online");
        ConfigCrypto crypto = new ConfigCrypto(env, "");
        assertFalse(crypto.isCryptoReady());
        assertTrue(crypto.isOnlineProfile());
        assertThrows(IllegalStateException.class, () -> crypto.encryptIfNeeded("x", true));
    }

    @Test
    void testWithoutKey_storesPlaintext() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ConfigCrypto crypto = new ConfigCrypto(env, "");
        assertEquals("x", crypto.encryptIfNeeded("x", true));
    }
}

package com.caopan.platform.geo.access;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.config.runtime.EffectiveAuthSettings;
import com.caopan.platform.geo.config.runtime.EffectiveCacheSettings;
import com.caopan.platform.geo.config.runtime.EffectiveConfigRegistry;
import com.caopan.platform.geo.entity.PlatformAccessClient;
import com.caopan.platform.geo.entity.PlatformAccessToken;
import com.caopan.platform.geo.mapper.PlatformAccessClientMapper;
import com.caopan.platform.geo.mapper.PlatformAccessTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenServiceTest {

    @Mock
    private PlatformAccessClientMapper clientMapper;
    @Mock
    private PlatformAccessTokenMapper tokenMapper;
    @Mock
    private ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AccessTokenService service;

    @BeforeEach
    void setUp() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        EffectiveConfigRegistry registry = new EffectiveConfigRegistry();
        Map<String, String> m = new HashMap<>();
        m.put("platform.geo.cache.redis-enabled", "false");
        m.put("platform.geo.auth.enabled", "false");
        m.put("platform.geo.auth.issue-secret", "");
        m.put("platform.geo.auth.redis-token-sync-enabled", "true");
        m.put("platform.geo.auth.valid-ttl-days", "365");
        m.put("platform.geo.auth.issue-lock-key-prefix", "platform:auth:issue-lock:");
        m.put("platform.geo.auth.valid-key-prefix", "platform:auth:valid:");
        m.put("platform.geo.auth.issue-lock-seconds", "60");
        m.put("platform.geo.auth.issue-lock-retry-times", "8");
        m.put("platform.geo.auth.issue-lock-retry-ms", "50");
        registry.replaceAll(m, Set.of());
        service = new AccessTokenService(
                clientMapper, tokenMapper, redisProvider, transactionManager,
                new EffectiveCacheSettings(registry),
                new EffectiveAuthSettings(registry));
    }

    @Test
    void issue_withoutExistingClient_throwsClientNotFound() {
        when(clientMapper.findByCode("crm")).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.issue("crm", "CRM"));
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void issue_allowIssueZero_throwsClientNotAllowed() {
        PlatformAccessClient client = enabledClient("crm", 1);
        client.setAllowIssue(0);
        when(clientMapper.findByCode("crm")).thenReturn(client);
        BizException ex = assertThrows(BizException.class, () -> service.issue("crm", null));
        assertEquals(ErrorCode.CLIENT_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void issue_disabledClient_throwsClientNotAllowed() {
        PlatformAccessClient client = enabledClient("crm", 0);
        client.setAllowIssue(1);
        when(clientMapper.findByCode("crm")).thenReturn(client);
        BizException ex = assertThrows(BizException.class, () -> service.issue("crm", null));
        assertEquals(ErrorCode.CLIENT_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void issue_happyPath_withExistingClient() {
        PlatformAccessClient client = enabledClient("crm", 1);
        client.setAllowIssue(1);
        when(clientMapper.findByCode("crm")).thenReturn(client);
        when(tokenMapper.listActiveTokenHashesByClientId(1L)).thenReturn(Collections.emptyList());
        doAnswer(inv -> {
            PlatformAccessToken t = inv.getArgument(0);
            t.setId(100L);
            return 1;
        }).when(tokenMapper).insert(any(PlatformAccessToken.class));

        AccessTokenService.IssuedToken issued = service.issue("crm", "CRM");
        assertEquals("crm", issued.clientCode());
        assertNotNull(issued.token());
        assertTrue(issued.token().length() >= 32);
        verify(tokenMapper).revokeActiveByClientId(1L);

        ArgumentCaptor<PlatformAccessToken> cap = ArgumentCaptor.forClass(PlatformAccessToken.class);
        verify(tokenMapper).insert(cap.capture());
        assertEquals(AccessTokenService.sha256Hex(issued.token()), cap.getValue().getTokenHash());
    }

    @Test
    void parse_validToken_returnsCaller() {
        String plain = "abcdefghijklmnopqrstuvwxyz012345";
        String hash = AccessTokenService.sha256Hex(plain);
        when(tokenMapper.findActiveCallerByHash(hash)).thenReturn(new TokenCallerRow(1L, 2L, "crm"));

        CallerContext ctx = service.parse(plain);
        assertEquals("crm", ctx.clientCode());
        assertEquals(2L, ctx.clientId());
    }

    @Test
    void parse_missing_throwsUnauthorized() {
        BizException ex = assertThrows(BizException.class, () -> service.parse(null));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void issue_invalidClientCode_throws() {
        BizException ex = assertThrows(BizException.class, () -> service.issue("a", null));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    private static PlatformAccessClient enabledClient(String code, int status) {
        PlatformAccessClient client = new PlatformAccessClient();
        client.setId(1L);
        client.setClientCode(code);
        client.setClientName(code);
        client.setStatus(status);
        return client;
    }
}

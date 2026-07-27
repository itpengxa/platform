package com.caopan.platform.geo.access;

import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
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

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    private AccessTokenService service;

    @BeforeEach
    void setUp() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        service = new AccessTokenService(
                clientMapper,
                tokenMapper,
                redisProvider,
                false,
                true,
                "platform:auth:issue-lock:",
                "platform:auth:valid:",
                30,
                8,
                50);
    }

    @Test
    void issue_createsClientAndToken() {
        when(clientMapper.findByCode("crm")).thenReturn(null);
        when(tokenMapper.listActiveTokenHashesByClientId(any())).thenReturn(Collections.emptyList());
        doAnswer(inv -> {
            PlatformAccessClient c = inv.getArgument(0);
            c.setId(10L);
            return 1;
        }).when(clientMapper).insert(any(PlatformAccessClient.class));
        doAnswer(inv -> {
            PlatformAccessToken t = inv.getArgument(0);
            t.setId(100L);
            return 1;
        }).when(tokenMapper).insert(any(PlatformAccessToken.class));

        AccessTokenService.IssuedToken issued = service.issue("crm", "CRM");
        assertEquals("crm", issued.getClientCode());
        assertNotNull(issued.getToken());
        assertTrue(issued.getToken().length() >= 32);
        verify(tokenMapper).revokeActiveByClientId(10L);

        ArgumentCaptor<PlatformAccessToken> cap = ArgumentCaptor.forClass(PlatformAccessToken.class);
        verify(tokenMapper).insert(cap.capture());
        assertEquals(AccessTokenService.sha256Hex(issued.getToken()), cap.getValue().getTokenHash());
    }

    @Test
    void parse_validToken_returnsCaller() {
        String plain = "abcdefghijklmnopqrstuvwxyz012345";
        String hash = AccessTokenService.sha256Hex(plain);
        TokenCallerRow row = new TokenCallerRow();
        row.setTokenId(1L);
        row.setClientId(2L);
        row.setClientCode("crm");
        when(tokenMapper.findActiveCallerByHash(hash)).thenReturn(row);

        CallerContext ctx = service.parse(plain);
        assertEquals("crm", ctx.getClientCode());
        assertEquals(2L, ctx.getClientId());
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

    @Test
    void issue_disabledClient_throws() {
        PlatformAccessClient client = new PlatformAccessClient();
        client.setId(1L);
        client.setClientCode("crm");
        client.setStatus(0);
        when(clientMapper.findByCode("crm")).thenReturn(client);
        BizException ex = assertThrows(BizException.class, () -> service.issue("crm", null));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }
}

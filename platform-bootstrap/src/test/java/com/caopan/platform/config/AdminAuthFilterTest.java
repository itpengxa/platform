package com.caopan.platform.config;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.admin.access.AdminAuthService;
import com.caopan.platform.geo.config.runtime.EffectiveAdminSettings;
import com.caopan.platform.geo.config.runtime.EffectiveConfigRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthFilterTest {

    @Mock
    private FilterChain filterChain;
    @Mock
    private AdminAuthService adminAuthService;

    private StaticMessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        messageSource.addMessage("error.admin_unauthorized", Locale.ENGLISH, "Admin unauthorized");
    }

    @Test
    void adminApi_withoutToken_returns401() throws Exception {
        AdminAuthFilter filter = newFilter("");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(adminRequest("GET", "/admin/geo/v1/regions/page"), resp, filterChain);
        assertEquals(401, resp.getStatus());
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adminApi_withValidToken_passes() throws Exception {
        when(adminAuthService.requireSession("tok-1"))
                .thenReturn(new AdminSessionCaller(1L, 1L, "admin", "管理员"));
        AdminAuthFilter filter = newFilter("");
        MockHttpServletRequest req = adminRequest("GET", "/admin/geo/v1/regions/page");
        req.addHeader(AdminAuthFilter.HEADER_ADMIN_TOKEN, "tok-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, filterChain);
        verify(filterChain).doFilter(req, resp);
    }

    @Test
    void adminApi_invalidToken_returns401() throws Exception {
        when(adminAuthService.requireSession(anyString()))
                .thenThrow(new BizException(ErrorCode.ADMIN_UNAUTHORIZED));
        AdminAuthFilter filter = newFilter("");
        MockHttpServletRequest req = adminRequest("GET", "/admin/geo/v1/regions/page");
        req.addHeader(AdminAuthFilter.HEADER_ADMIN_TOKEN, "bad");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, filterChain);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void login_isPublic() throws Exception {
        AdminAuthFilter filter = newFilter("legacy");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(adminRequest("POST", "/admin/platform/v1/auth/login"), resp, filterChain);
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staticHtmlGet_withoutToken_passes() throws Exception {
        AdminAuthFilter filter = newFilter("legacy");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(adminRequest("GET", "/admin/index.html"), resp, filterChain);
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void legacySecret_stillAccepted() throws Exception {
        AdminAuthFilter filter = newFilter("my-secret");
        MockHttpServletRequest req = adminRequest("GET", "/admin/geo/v1/regions/page");
        req.addHeader(AdminAuthFilter.HEADER_ADMIN_SECRET, "my-secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, filterChain);
        verify(filterChain).doFilter(req, resp);
    }

    @Test
    void isAdminApi_detectsV1Paths() {
        assertTrue(AdminAuthFilter.isAdminApi("/admin/geo/v1/regions/page"));
        assertFalse(AdminAuthFilter.isAdminApi("/admin/index.html"));
    }

    @Test
    void adminDisabled_rejectsApiButAllowsStatic() throws Exception {
        AdminAuthFilter filter = newFilter("", false);
        MockHttpServletResponse apiResp = new MockHttpServletResponse();
        filter.doFilter(adminRequest("GET", "/admin/geo/v1/regions/page"), apiResp, filterChain);
        assertEquals(401, apiResp.getStatus());
        verify(filterChain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        MockHttpServletResponse htmlResp = new MockHttpServletResponse();
        filter.doFilter(adminRequest("GET", "/admin/index.html"), htmlResp, filterChain);
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private AdminAuthFilter newFilter(String secret) {
        return newFilter(secret, true);
    }

    private AdminAuthFilter newFilter(String secret, boolean enabled) {
        EffectiveConfigRegistry registry = new EffectiveConfigRegistry();
        registry.replaceAll(Map.of(
                "platform.geo.admin.enabled", enabled ? "true" : "false",
                "platform.geo.admin.secret", secret == null ? "" : secret,
                "platform.geo.admin.path-prefix", "/admin",
                "platform.geo.admin.session-ttl-days", "7"
        ), Set.of());
        EffectiveAdminSettings settings = new EffectiveAdminSettings(registry);
        return new AdminAuthFilter(new ObjectMapper(), messageSource, settings, adminAuthService);
    }

    private static MockHttpServletRequest adminRequest(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}

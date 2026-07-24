package com.caopan.platform.config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GeoInternalAuthFilterTest {

    private static final String TOKEN = "unit-test-token-16";

    @Mock
    private FilterChain filterChain;

    private StaticMessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        messageSource.addMessage("error.unauthorized", Locale.SIMPLIFIED_CHINESE, "未授权");
    }

    @Test
    void disabled_skipsFilter() {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, false, TOKEN);
        assertTrue(filter.shouldNotFilter(geoRequest()));
    }

    @Test
    void enabled_missingToken_returns401() throws Exception {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, true, TOKEN);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(geoRequest(), resp, filterChain);
        assertEquals(401, resp.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void enabled_wrongToken_returns401() throws Exception {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, true, TOKEN);
        MockHttpServletRequest req = geoRequest();
        req.addHeader("X-Platform-Token", "wrong-token-xxxxx");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, filterChain);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void enabled_headerToken_passes() throws Exception {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, true, TOKEN);
        MockHttpServletRequest req = geoRequest();
        req.addHeader("X-Platform-Token", TOKEN);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, filterChain);
        assertEquals(200, resp.getStatus());
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void enabled_bearerToken_passes() throws Exception {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, true, TOKEN);
        MockHttpServletRequest req = geoRequest();
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, filterChain);
        assertEquals(200, resp.getStatus());
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void enabled_emptyConfiguredToken_returns401() throws Exception {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, true, "  ");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(geoRequest(), resp, filterChain);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void nonGeoPath_skippedEvenWhenEnabled() {
        GeoInternalAuthFilter filter = new GeoInternalAuthFilter(
                new ObjectMapper(), messageSource, true, TOKEN);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        assertTrue(filter.shouldNotFilter(req));
        assertFalse(filter.shouldNotFilter(geoRequest()));
    }

    private static MockHttpServletRequest geoRequest() {
        return new MockHttpServletRequest("GET", "/api/geo/v1/countries");
    }
}

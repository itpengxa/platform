package com.caopan.platform.config;

import com.caopan.platform.config.GeoRateLimitProperties;
import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoIpRateLimitFilterTest {

    @Mock
    private ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private FilterChain filterChain;

    private StaticMessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        messageSource.addMessage("error.rate_limited", Locale.ENGLISH, "Too many requests");
    }

    @Test
    void resolveClientIp_ignoresXffWhenNotTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("10.0.0.1", GeoIpRateLimitFilter.resolveClientIp(req, false));
    }

    @Test
    void resolveClientIp_usesXffWhenTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        assertEquals("1.2.3.4", GeoIpRateLimitFilter.resolveClientIp(req, true));
    }

    @Test
    void localLimit_secondRequestWithinInterval_returns429() throws Exception {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        GeoIpRateLimitFilter filter = newFilter(false, false);

        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/countries"), ok, filterChain);
        assertEquals(200, ok.getStatus());

        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/countries"), limited, filterChain);
        assertEquals(429, limited.getStatus());
    }

    @Test
    void treePath_trailingSlash_usesTreeBucket() throws Exception {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        GeoIpRateLimitFilter filter = newFilter(false, false);

        filter.doFilter(geoRequest("/api/geo/v1/regions/tree/"), new MockHttpServletResponse(), filterChain);
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/regions/tree"), limited, filterChain);
        assertEquals(429, limited.getStatus());
    }

    @Test
    void redisFailClosed_rejectsWhenRedisThrows() throws Exception {
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RuntimeException("redis down"));

        GeoIpRateLimitFilter filter = newFilter(true, true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/countries"), resp, filterChain);

        assertEquals(429, resp.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void redisDirtyKeyWithoutTtl_isRepairedAndAllowsRequest() throws Exception {
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(false)
                .thenReturn(true);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenReturn(-1L);
        when(redisTemplate.delete(anyString())).thenReturn(Boolean.TRUE);

        GeoIpRateLimitFilter filter = newFilter(true, false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/countries"), resp, filterChain);

        assertEquals(200, resp.getStatus());
        verify(filterChain).doFilter(any(), any());
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void shouldNotFilter_whenDisabledOrNonGeo() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        GeoCacheProperties cache = new GeoCacheProperties(
                true, 10_000L, 10L, 24L, 24L, 24L, 24L, 12L, 0L, 30L, 20_000, 4);
        GeoRateLimitProperties disabledRate = new GeoRateLimitProperties(
                false, false, false, 1000L, 1000L, 2000L);
        GeoIpRateLimitFilter disabled = new GeoIpRateLimitFilter(
                new ObjectMapper(), messageSource, redisProvider, cache, disabledRate);
        assertTrue(disabled.shouldNotFilter(geoRequest("/api/geo/v1/countries")));

        GeoIpRateLimitFilter enabled = newFilter(false, false);
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/health");
        assertTrue(enabled.shouldNotFilter(other));
        assertFalse(enabled.shouldNotFilter(geoRequest("/api/geo/v1/countries")));
        assertFalse(enabled.shouldNotFilter(
                new MockHttpServletRequest("POST", "/api/platform/v1/auth/token/issue")));
    }

    @Test
    void searchPath_usesIndependentBucketAt1s() throws Exception {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        GeoIpRateLimitFilter filter = newFilter(false, false);

        // 先打 countries（default 桶），不应占用 search 桶
        filter.doFilter(geoRequest("/api/geo/v1/countries"), new MockHttpServletResponse(), filterChain);

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/regions/search"), first, filterChain);
        assertEquals(200, first.getStatus());

        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(geoRequest("/api/geo/v1/regions/search"), limited, filterChain);
        assertEquals(429, limited.getStatus());
    }

    @Test
    void order_isHighPrecedence() {
        int rateLimit = GeoIpRateLimitFilter.class.getAnnotation(Order.class).value();
        assertTrue(rateLimit < org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
    }

    private GeoIpRateLimitFilter newFilter(boolean redisEnabled, boolean failClosed) {
        GeoCacheProperties cache = new GeoCacheProperties(
                redisEnabled, 10_000L, 10L, 24L, 24L, 24L, 24L, 12L, 0L, 30L, 20_000, 4);
        GeoRateLimitProperties rate = new GeoRateLimitProperties(
                true, false, failClosed, 1000L, 1000L, 2000L);
        return new GeoIpRateLimitFilter(
                new ObjectMapper(),
                messageSource,
                redisProvider,
                cache,
                rate);
    }

    private static MockHttpServletRequest geoRequest(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRemoteAddr("127.0.0.1");
        return req;
    }
}

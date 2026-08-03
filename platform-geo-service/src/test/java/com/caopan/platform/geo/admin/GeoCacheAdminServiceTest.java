package com.caopan.platform.geo.admin;

import com.caopan.platform.geo.cache.CacheInvalidationBroadcaster;
import com.caopan.platform.geo.cache.CacheInvalidationMessage;
import com.caopan.platform.geo.cache.GeoCacheKeys;
import com.caopan.platform.geo.cache.TieredCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoCacheAdminServiceTest {

    @Mock
    private TieredCache tieredCache;
    @Mock
    private CacheInvalidationBroadcaster broadcaster;

    private GeoCacheAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(broadcaster.instanceId()).thenReturn("inst-1");
        lenient().when(broadcaster.isBroadcastAvailable()).thenReturn(true);
        service = new GeoCacheAdminService(tieredCache, broadcaster);
        service.bindBroadcastHandler();
    }

    @Test
    void clearAll_dryRun_doesNotMutate() {
        when(tieredCache.scanRedisKeys(GeoCacheKeys.DATA_CLEAR_PATTERNS)).thenReturn(Set.of(
                "platform:geo:region:1", "platform:geo:path:1"));

        GeoCacheAdminService.ClearResult r = service.clear(
                new GeoCacheAdminService.ClearRequest("ALL", null, null, null, true), "admin");

        assertTrue(r.dryRun());
        assertEquals(2, r.deletedRedisKeys());
        assertFalse(r.localL1Cleared());
        verify(tieredCache, never()).clearRedisByPatterns(any(), anyBoolean());
        verify(tieredCache, never()).invalidateLocalAll();
        verify(broadcaster, never()).publish(any());
    }

    @Test
    void clearAll_executesAndBroadcasts() {
        when(tieredCache.clearRedisByPatterns(eq(GeoCacheKeys.DATA_CLEAR_PATTERNS), eq(false))).thenReturn(5L);

        GeoCacheAdminService.ClearResult r = service.clear(
                new GeoCacheAdminService.ClearRequest("ALL", null, null, null, false), "admin");

        assertEquals(5, r.deletedRedisKeys());
        assertTrue(r.localL1Cleared());
        assertTrue(r.broadcast());
        verify(tieredCache).invalidateLocalAll();
        ArgumentCaptor<CacheInvalidationMessage> cap = ArgumentCaptor.forClass(CacheInvalidationMessage.class);
        verify(broadcaster).publish(cap.capture());
        assertEquals("ALL", cap.getValue().scope());
    }

    @Test
    void evictKeys_filtersUnsafe() {
        when(tieredCache.deleteRedisKeysExact(any(), eq(false))).thenReturn(1L);

        GeoCacheAdminService.ClearResult r = service.evictKeys(List.of(
                "platform:geo:region:9",
                "platform:geo:rl:1.1.1.1:default",
                "platform:auth:valid:x"
        ), false, "admin");

        assertEquals(1, r.deletedRedisKeys());
        verify(tieredCache).deleteRedisKeysExact(eq(List.of("platform:geo:region:9")), eq(false));
        verify(tieredCache).invalidateLocal(List.of("platform:geo:region:9"));
    }
}

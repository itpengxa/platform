package com.caopan.platform.geo.service.support;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.cache.GeoCacheProperties;
import com.caopan.platform.geo.cache.TieredCache;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.mapper.GeoCountryMapper;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoDataCacheTest {

    @Mock
    private GeoCountryMapper geoCountryMapper;
    @Mock
    private GeoRegionMapper geoRegionMapper;

    private GeoDataCache geoDataCache;

    @BeforeEach
    void setUp() {
        TieredCache tieredCache = new TieredCache(
                Caffeine.newBuilder().maximumSize(1000).build(),
                null,
                new ObjectMapper(),
                false,
                Duration.ofSeconds(30));
        GeoCacheProperties props = new GeoCacheProperties(
                true, 10_000L, 10L, 24L, 24L, 24L, 24L, 12L, 0L, 30L, 100, 4);
        geoDataCache = new GeoDataCache(geoCountryMapper, geoRegionMapper, tieredCache, props);
    }

    @Test
    void listChildren_parentMissing_throwsAndNegativeCaches() {
        when(geoRegionMapper.findEnabledById(99L)).thenReturn(null);

        BizException first = assertThrows(BizException.class, () -> geoDataCache.listChildren(99L));
        assertEquals(ErrorCode.PARENT_NOT_FOUND.getCode(), first.getCode());

        BizException second = assertThrows(BizException.class, () -> geoDataCache.listChildren(99L));
        assertEquals(ErrorCode.PARENT_NOT_FOUND.getCode(), second.getCode());
        // 负缓存后不再打 DB
        verify(geoRegionMapper, times(1)).findEnabledById(99L);
    }

    @Test
    void listChildren_ok() {
        GeoRegion parent = region(1L, 1, "VN", "/1/");
        GeoRegion child = region(2L, 2, "VN", "/1/2/");
        when(geoRegionMapper.findEnabledById(1L)).thenReturn(parent);
        when(geoRegionMapper.listByParentId(1L)).thenReturn(List.of(child));

        List<GeoRegion> list = geoDataCache.listChildren(1L);
        assertEquals(1, list.size());
        assertEquals(2L, list.get(0).getId());
    }

    @Test
    void loadTreeNodes_invalidDepth() {
        BizException ex = assertThrows(BizException.class,
                () -> geoDataCache.loadTreeNodes("VN", null, 0));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void loadTreeNodes_countryRootCapsDepthAndLoads() {
        GeoRegion root = region(1L, 1, "VN", "/1/");
        when(geoRegionMapper.findCountryByCode("VN")).thenReturn(root);
        when(geoRegionMapper.listSubtree(eq("VN"), eq("/1/"), eq(4), eq(100)))
                .thenReturn(List.of(root));

        // depth=5 国家级应封顶为 4，并按 depth=4 缓存键加载（maxLevel=1+4-1=4）
        GeoDataCache.TreeLoadResult result = geoDataCache.loadTreeNodes("VN", null, 5);
        assertEquals(1L, result.getRoot().getId());
        verify(geoRegionMapper).listSubtree("VN", "/1/", 4, 100);
    }

    @Test
    void loadTreeNodes_hitMaxRows_rejects() {
        GeoRegion root = region(1L, 1, "VN", "/1/");
        when(geoRegionMapper.findCountryByCode("VN")).thenReturn(root);
        when(geoRegionMapper.listSubtree(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.nCopies(100, root));

        BizException ex = assertThrows(BizException.class,
                () -> geoDataCache.loadTreeNodes("VN", null, 2));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void loadTreeNodes_countryMissing_negativeCache() {
        when(geoRegionMapper.findCountryByCode("ZZ")).thenReturn(null);
        BizException first = assertThrows(BizException.class,
                () -> geoDataCache.loadTreeNodes("ZZ", null, 2));
        assertEquals(ErrorCode.COUNTRY_NOT_FOUND.getCode(), first.getCode());

        assertThrows(BizException.class, () -> geoDataCache.loadTreeNodes("ZZ", null, 2));
        verify(geoRegionMapper, times(1)).findCountryByCode("ZZ");
    }

    @Test
    void loadTreeNodes_rootMismatch_regionNotFound() {
        when(geoRegionMapper.findEnabledById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> geoDataCache.loadTreeNodes("VN", 9L, 2));
        assertEquals(ErrorCode.REGION_NOT_FOUND.getCode(), ex.getCode());
        verify(geoRegionMapper, times(0)).findCountryByCode(anyString());
        verify(geoRegionMapper, times(0)).listSubtree(anyString(), anyString(), anyInt(), anyInt());
    }

    private static GeoRegion region(long id, int level, String country, String path) {
        GeoRegion r = new GeoRegion();
        r.setId(id);
        r.setLevel(level);
        r.setCountryCode(country);
        r.setPath(path);
        r.setParentId(level == 1 ? 0L : 1L);
        r.setName("n" + id);
        return r;
    }
}

package com.caopan.platform.geo.service.impl;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.entity.GeoCountry;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.service.support.GeoDataCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoServiceImplTest {

    @Mock
    private GeoDataCache geoDataCache;

    @InjectMocks
    private GeoServiceImpl geoService;

    @Test
    void listCountries_rejectsWildcardKeyword() {
        BizException ex = assertThrows(BizException.class,
                () -> geoService.listCountries("zh", "Vi%"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(geoDataCache, never()).listCountries(anyString());
    }

    @Test
    void listCountries_ok() {
        GeoCountry c = new GeoCountry();
        c.setId(1L);
        c.setIso2("VN");
        c.setName("Vietnam");
        c.setNameEn("Vietnam");
        c.setNameCh("越南");
        when(geoDataCache.listCountries(null)).thenReturn(List.of(c));

        assertEquals(1, geoService.listCountries("zh", null).size());
        assertEquals("越南", geoService.listCountries("zh", null).get(0).getDisplayName());
    }

    @Test
    void listChildren_invalidParentId() {
        assertThrows(BizException.class, () -> geoService.listChildren(null, "en"));
        assertThrows(BizException.class, () -> geoService.listChildren(0L, "en"));
    }

    @Test
    void search_requiresCountryAndMinKeyword() {
        assertThrows(BizException.class, () -> geoService.search("a", "VN", null, 20, "en"));
        assertThrows(BizException.class, () -> geoService.search("Ha", null, null, 20, "en"));
        assertThrows(BizException.class, () -> geoService.search("Ha%", "VN", null, 20, "en"));
        assertThrows(BizException.class, () -> geoService.search("Hanoi", "VN", null, 0, "en"));
        verify(geoDataCache, never()).search(anyString(), anyString(), isNull(), anyInt());
    }

    @Test
    void search_ok() {
        GeoRegion hit = new GeoRegion();
        hit.setId(3L);
        hit.setPath("/1/2/3/");
        hit.setName("Hanoi");
        hit.setNameEn("Hanoi");
        hit.setNameCh("河内");
        hit.setCountryCode("VN");
        hit.setLevel(3);
        when(geoDataCache.search(eq("Ha"), eq("VN"), isNull(), eq(20))).thenReturn(List.of(hit));
        when(geoDataCache.listByIds(List.of(1L, 2L, 3L))).thenReturn(Collections.emptyList());

        assertEquals(1, geoService.search("Ha", "vn", null, null, "en").size());
    }

    @Test
    void getPath_invalidId() {
        assertThrows(BizException.class, () -> geoService.getPath(-1L, "en"));
    }
}

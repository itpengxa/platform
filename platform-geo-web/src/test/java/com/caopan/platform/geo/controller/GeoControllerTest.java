package com.caopan.platform.geo.controller;

import com.caopan.platform.api.service.GeoService;
import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller 纯单元测试（Mockito），不启动 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
class GeoControllerTest {

    @Mock
    private GeoService geoService;

    @InjectMocks
    private GeoController geoController;

    @Test
    void countries_delegatesToService() {
        CountryVO vo = new CountryVO();
        vo.setIso2("VN");
        when(geoService.listCountries("zh", null)).thenReturn(List.of(vo));

        Result<List<CountryVO>> result = geoController.countries("zh", null);

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("VN", result.getData().get(0).getIso2());
        verify(geoService).listCountries("zh", null);
    }

    @Test
    void children_invalidParentId_throws() {
        BizException ex = assertThrows(BizException.class,
                () -> geoController.children(0L, "en"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void children_ok() {
        RegionVO vo = new RegionVO();
        vo.setId(2L);
        when(geoService.listChildren(1L, "en")).thenReturn(List.of(vo));

        Result<List<RegionVO>> result = geoController.children(1L, "en");
        assertEquals(0, result.getCode());
        assertEquals(2L, result.getData().get(0).getId());
    }

    @Test
    void tree_delegates() {
        RegionTreeVO tree = new RegionTreeVO();
        tree.setId(1L);
        when(geoService.getTree("VN", null, 3, "zh")).thenReturn(tree);

        Result<RegionTreeVO> result = geoController.tree("VN", null, 3, "zh");
        assertEquals(0, result.getCode());
        assertEquals(1L, result.getData().getId());
    }

    @Test
    void path_delegates() {
        RegionVO vo = new RegionVO();
        vo.setId(1L);
        when(geoService.getPath(1L, "en")).thenReturn(List.of(vo));

        Result<List<RegionVO>> result = geoController.path(1L, "en");
        assertEquals(0, result.getCode());
        assertEquals(1L, result.getData().get(0).getId());
        verify(geoService).getPath(1L, "en");
    }

    @Test
    void path_invalidId_throws() {
        BizException ex = assertThrows(BizException.class,
                () -> geoController.path(0L, "en"));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void search_delegates() {
        when(geoService.search("Ha", "VN", null, 20, "en")).thenReturn(List.of(new RegionSearchVO()));

        Result<List<RegionSearchVO>> result = geoController.search("Ha", "VN", null, 20, "en");
        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        verify(geoService).search("Ha", "VN", null, 20, "en");
    }
}

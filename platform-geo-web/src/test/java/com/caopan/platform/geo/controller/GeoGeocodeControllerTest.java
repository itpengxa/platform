package com.caopan.platform.geo.controller;

import com.caopan.platform.api.vo.ReverseGeocodeVO;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.report.ReverseGeocodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeoGeocodeControllerTest {

    @Mock
    private ReverseGeocodeService reverseGeocodeService;

    @InjectMocks
    private GeoGeocodeController controller;

    @Test
    void reverse_usesLon() {
        ReverseGeocodeVO vo = new ReverseGeocodeVO();
        vo.setDisplayName("x");
        when(reverseGeocodeService.reverse(20.78, 105.35, "zh", null)).thenReturn(vo);

        Result<ReverseGeocodeVO> result = controller.reverse(20.78, 105.35, null, "zh", null);
        assertEquals(0, result.getCode());
        assertEquals("x", result.getData().getDisplayName());
        verify(reverseGeocodeService).reverse(20.78, 105.35, "zh", null);
    }

    @Test
    void reverse_fallsBackToLng() {
        ReverseGeocodeVO vo = new ReverseGeocodeVO();
        when(reverseGeocodeService.reverse(1.0, 2.0, null, "VN")).thenReturn(vo);

        Result<ReverseGeocodeVO> result = controller.reverse(1.0, null, 2.0, null, "VN");
        assertEquals(0, result.getCode());
        verify(reverseGeocodeService).reverse(1.0, 2.0, null, "VN");
    }

    @Test
    void reverse_missingLon_throws() {
        BizException ex = assertThrows(BizException.class,
                () -> controller.reverse(1.0, null, null, null, null));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }
}

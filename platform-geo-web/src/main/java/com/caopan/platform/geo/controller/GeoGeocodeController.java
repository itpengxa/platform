package com.caopan.platform.geo.controller;

import com.caopan.platform.api.vo.ReverseGeocodeVO;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.report.ReverseGeocodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经纬度反查详细地址（用户侧 Token 鉴权）。
 * <p>优先本库 {@code geo_region} 坐标近邻（街道级向上）；无坐标覆盖时回退 Nominatim 名匹配。
 * 调用方应以 {@code match.path} 为准。</p>
 */
@RestController
@RequestMapping("/api/geo/v1/geocode")
public class GeoGeocodeController {

    private final ReverseGeocodeService reverseGeocodeService;

    public GeoGeocodeController(ReverseGeocodeService reverseGeocodeService) {
        this.reverseGeocodeService = reverseGeocodeService;
    }

    /**
     * 经纬度 → 详细地址。
     *
     * @param lat         纬度（必填）
     * @param lon         经度（与 lng 二选一）
     * @param lng         经度别名
     * @param lang        语言偏好 local/en/zh，可空
     * @param countryCode 可选 ISO2，缩小本库近邻范围
     */
    @GetMapping("/reverse")
    public Result<ReverseGeocodeVO> reverse(
            @RequestParam Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String countryCode) {
        Double longitude = lon != null ? lon : lng;
        if (longitude == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return Result.ok(reverseGeocodeService.reverse(lat, longitude, lang, countryCode));
    }
}

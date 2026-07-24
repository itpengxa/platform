package com.caopan.platform.geo.controller;

import com.caopan.platform.api.service.GeoService;
import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 2026-07-24 GEO-001 行政区划查询 API（仅 Controller）
 */
@RestController
@RequestMapping("/api/geo/v1")
/**
 * 行政区划 REST 控制器。暴露 5 个查询接口 + 1 个国旗资源接口。
 * 所有接口均为只读（GET），无需鉴权（内网开放）。
 */
public class GeoController {

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);
    @Resource
    private  GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    @GetMapping("/countries")
    public Result<List<CountryVO>> countries(
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String keyword) {
        log.info("API countries, lang={}, keyword={}", lang, keyword);
        return Result.ok(geoService.listCountries(lang, keyword));
    }

    @GetMapping("/regions/children")
    public Result<List<RegionVO>> children(
            @RequestParam Long parentId,
            @RequestParam(required = false) String lang) {
        log.info("API children, parentId={}, lang={}", parentId, lang);
        if (parentId == null || parentId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return Result.ok(geoService.listChildren(parentId, lang));
    }

    @GetMapping("/regions/tree")
    public Result<RegionTreeVO> tree(
            @RequestParam String countryCode,
            @RequestParam(required = false) Long rootId,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) String lang) {
        log.info("API tree, countryCode={}, rootId={}, depth={}, lang={}", countryCode, rootId, depth, lang);
        return Result.ok(geoService.getTree(countryCode, rootId, depth, lang));
    }

    @GetMapping("/regions/{id}/path")
    public Result<List<RegionVO>> path(
            @PathVariable Long id,
            @RequestParam(required = false) String lang) {
        log.info("API path, id={}, lang={}", id, lang);
        return Result.ok(geoService.getPath(id, lang));
    }

    @GetMapping("/regions/search")
    public Result<List<RegionSearchVO>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false) String lang) {
        log.info("API search, keyword={}, countryCode={}, level={}, limit={}, lang={}",
                keyword, countryCode, level, limit, lang);
        return Result.ok(geoService.search(keyword, countryCode, level, limit, lang));
    }
}

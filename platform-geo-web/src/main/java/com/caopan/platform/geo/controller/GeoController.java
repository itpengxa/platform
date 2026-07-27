package com.caopan.platform.geo.controller;

import com.caopan.platform.api.service.GeoService;
import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;
import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行政区划 REST 控制器（GEO-001 / platform-geo-web）。
 * <p>暴露国家列表、子级级联、子树、祖先链、关键词搜索等只读接口，统一包装为 {@link Result}。
 * 过滤器顺序：IP 限流在鉴权之前；test profile 默认可不鉴权，online/prod 须开启内部 Token
 *（{@code X-Platform-Token} / Bearer）并配合限流。</p>
 */
@RestController
@RequestMapping("/api/geo/v1")
public class GeoController {

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);

    private final GeoService geoService;

    /**
     * 注入依赖构造。
     *
     * @param geoService 行政区划服务
     */
    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    /**
     * 查询启用国家列表（默认不含 icon_base64）。
     *
     * @param lang    语言偏好（local/en/zh，可空）
     * @param keyword 关键词，可空（iso2 或名称前缀）
     * @return 统一包装的国家列表
     */
    @GetMapping("/countries")
    public Result<List<CountryVO>> countries(
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String keyword) {
        log.info("API countries, lang={}, keyword={}", lang, keyword);
        return Result.ok(geoService.listCountries(lang, keyword));
    }

    /**
     * 查询指定父节点的直属子级列表（级联下钻）。
     *
     * @param parentId 父节点 ID（必填，&gt;0）
     * @param lang     语言偏好（local/en/zh，可空）
     * @return 统一包装的子级区划列表
     */
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

    /**
     * 按国家编码组装行政区划树。
     * <p>depth 可空，缺省为 3（国家→省→市），范围 1~5；国家级根 depth&gt;4 封顶为 4（至区县），禁止无界全量加载。</p>
     *
     * @param countryCode 国家 ISO2 编码（必填）
     * @param rootId      可选根节点 ID；为空则从国家级节点开始
     * @param depth       可选深度限制（1~5），缺省 3
     * @param lang        语言偏好（local/en/zh，可空）
     * @return 统一包装的树根节点
     */
    @GetMapping("/regions/tree")
    public Result<RegionTreeVO> tree(
            @RequestParam String countryCode,
            @RequestParam(required = false) Long rootId,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) String lang) {
        log.info("API tree, countryCode={}, rootId={}, depth={}, lang={}", countryCode, rootId, depth, lang);
        return Result.ok(geoService.getTree(countryCode, rootId, depth, lang));
    }

    /**
     * 按区划 ID 回显从国家到当前节点的有序祖先链。
     *
     * @param id   区划 ID
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 统一包装的祖先链（国家→…→当前）
     */
    @GetMapping("/regions/{id}/path")
    public Result<List<RegionVO>> path(
            @PathVariable Long id,
            @RequestParam(required = false) String lang) {
        log.info("API path, id={}, lang={}", id, lang);
        return Result.ok(geoService.getPath(id, lang));
    }

    /**
     * 按关键词搜索行政区划。
     * <p>countryCode 必填（ISO2）；关键词按前缀匹配（非前后模糊），长度≥2，禁止 %/_。</p>
     *
     * @param keyword     关键词（必填，最长 64）
     * @param countryCode 国家过滤（ISO2，必填）
     * @param level       可选层级过滤
     * @param limit       返回条数，默认 20，范围 1~100
     * @param lang        语言偏好（local/en/zh，可空）
     * @return 统一包装的搜索结果（含 fullPathName）
     */
    @GetMapping("/regions/search")
    public Result<List<RegionSearchVO>> search(
            @RequestParam String keyword,
            @RequestParam String countryCode,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false) String lang) {
        log.info("API search, keyword={}, countryCode={}, level={}, limit={}, lang={}",
                keyword, countryCode, level, limit, lang);
        return Result.ok(geoService.search(keyword, countryCode, level, limit, lang));
    }
}

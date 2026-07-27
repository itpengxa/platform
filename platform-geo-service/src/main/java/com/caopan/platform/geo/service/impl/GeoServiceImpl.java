package com.caopan.platform.geo.service.impl;

import com.caopan.platform.api.service.GeoService;
import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.util.LangUtil;
import com.caopan.platform.geo.entity.GeoCountry;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.service.support.GeoDataCache;
import com.caopan.platform.geo.service.support.PathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 行政区划服务实现（GEO-001 / platform-geo-service）。
 * <p>实现 {@link GeoService}：多语言 displayName、树组装、祖先链、国家维度前缀搜索。
 * 数据访问经 {@link GeoDataCache} 走三级缓存（L1 Caffeine → L2 Redis → L3 DB），只读无事务。
 * countries 列表默认不含 icon_base64；search 强制国家 + 前缀匹配。</p>
 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);

    private final GeoDataCache geoDataCache;

    /**
     * 注入依赖构造。
     *
     * @param geoDataCache 地理数据缓存门面
     */
    public GeoServiceImpl(GeoDataCache geoDataCache) {
        this.geoDataCache = geoDataCache;
    }

    /**
     * 查询启用国家列表，支持语言与关键词过滤。
     *
     * @param lang    语言偏好（local/en/zh，可空）
     * @param keyword 关键词，可空（iso2 或名称前缀）
     * @return 国家 VO 列表（不含大体积 iconBase64）
     */
    @Override
    public List<CountryVO> listCountries(String lang, String keyword) {
        log.info("listCountries start, lang={}, keyword={}", lang, keyword);
        String kw = trimToNull(keyword);
        if (kw != null) {
            if (kw.length() > 64 || kw.length() < 1
                    || kw.indexOf('%') >= 0 || kw.indexOf('_') >= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            if (kw.length() == 2) {
                kw = kw.toUpperCase();
            }
        }
        List<GeoCountry> list = geoDataCache.listCountries(kw);
        List<CountryVO> result = new ArrayList<>(list.size());
        for (GeoCountry c : list) {
            result.add(toCountryVO(c, lang));
        }
        log.info("listCountries end, size={}", result.size());
        return result;
    }

    /**
     * 按父节点 ID 查询直属子行政区划列表。
     *
     * @param parentId 父节点 ID（必填，&gt;0）
     * @param lang     语言偏好（local/en/zh，可空）
     * @return 子级区划 VO 列表（含 isLeaf）
     */
    @Override
    public List<RegionVO> listChildren(Long parentId, String lang) {
        log.info("listChildren start, parentId={}, lang={}", parentId, lang);
        if (parentId == null || parentId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        // listChildren 内部校验父节点；加载时会回填 region 缓存，下方 find 命中 L1
        List<GeoRegion> children = geoDataCache.listChildren(parentId);
        GeoRegion parent = geoDataCache.findEnabledById(parentId);
        children = geoDataCache.preferNextLevel(parent, children);
        Map<Long, Integer> childCounts = countChildMap(children);
        List<RegionVO> result = new ArrayList<>(children.size());
        for (GeoRegion r : children) {
            RegionVO vo = toRegionVO(r, lang);
            // isLeaf 以实际是否有子节点为准，不写死 3/4/5 级
            vo.setIsLeaf(childCounts.getOrDefault(r.getId(), 0) == 0);
            result.add(vo);
        }
        log.info("listChildren end, parentId={}, size={}", parentId, result.size());
        return result;
    }

    /**
     * 批量统计各区划节点的启用子节点数量，用于判定 isLeaf。
     *
     * @param regions 待统计的区划列表
     * @return parentId → 子节点数；空入参返回空 Map
     */
    private Map<Long, Integer> countChildMap(List<GeoRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = new ArrayList<>(regions.size());
        for (GeoRegion r : regions) {
            ids.add(r.getId());
        }
        return geoDataCache.countChildren(ids);
    }

    /**
     * 按国家编码（及可选根节点、深度）组装行政区划树。
     *
     * @param countryCode 国家 ISO2 编码
     * @param rootId      根节点 ID，可空
     * @param depth       深度限制 1~5，可空
     * @param lang        语言偏好（local/en/zh，可空）
     * @return 树根节点（含嵌套 children）
     */
    @Override
    public RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang) {
        log.info("getTree start, countryCode={}, rootId={}, depth={}, lang={}", countryCode, rootId, depth, lang);
        GeoDataCache.TreeLoadResult loaded = geoDataCache.loadTreeNodes(countryCode, rootId, depth);
        RegionTreeVO tree = buildTree(loaded.getNodes(), loaded.getRoot().getId(), lang);
        if (tree == null) {
            tree = toRegionTreeVO(loaded.getRoot(), lang);
        }
        log.info("getTree end, countryCode={}, nodeCount={}", countryCode, loaded.getNodes().size());
        return tree;
    }

    /**
     * 按区划 ID 回显从国家到当前节点的有序祖先链。
     *
     * @param id   区划 ID
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 祖先链（国家→…→当前）
     */
    @Override
    public List<RegionVO> getPath(Long id, String lang) {
        log.info("getPath start, id={}, lang={}", id, lang);
        if (id == null || id <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        List<GeoRegion> regions = geoDataCache.listPathEntities(id);
        Map<Long, GeoRegion> map = new HashMap<>();
        for (GeoRegion r : regions) {
            map.put(r.getId(), r);
        }
        GeoRegion current = map.get(id);
        // 祖先链必须包含当前节点；缺失则直接失败，禁止回退到列表末元素（可能是祖先）
        if (current == null) {
            throw new BizException(ErrorCode.REGION_NOT_FOUND);
        }
        List<Long> ids = PathUtil.parsePathIds(current.getPath());
        if (ids.isEmpty()) {
            return Collections.singletonList(toRegionVO(current, lang));
        }
        List<RegionVO> path = new ArrayList<>();
        for (Long pathId : ids) {
            GeoRegion r = map.get(pathId);
            if (r != null) {
                path.add(toRegionVO(r, lang));
            }
        }
        log.info("getPath end, id={}, depth={}", id, path.size());
        return path;
    }

    /**
     * 按关键词搜索行政区划，返回命中节点及全路径名称。
     * <p>强制 countryCode（ISO2）+ 名称前缀匹配，禁止 %/_ 通配符，避免全表扫。</p>
     *
     * @param keyword     关键词（必填，长度 2~64）
     * @param countryCode 国家 ISO2 编码（必填）
     * @param level       层级过滤，可空
     * @param limit       返回条数上限（缺省 20，范围 1~100）
     * @param lang        语言偏好（local/en/zh，可空）
     * @return 搜索结果（含 fullPathName）
     */
    @Override
    public List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang) {
        log.info("search start, keyword={}, countryCode={}, level={}, limit={}, lang={}",
                keyword, countryCode, level, limit, lang);
        if (!StringUtils.hasText(keyword) || keyword.trim().length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String kw = keyword.trim();
        // 最短 2 字；禁止通配符，避免人为构造慢查询
        if (kw.length() < 2 || kw.indexOf('%') >= 0 || kw.indexOf('_') >= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        int lim = limit == null ? 20 : limit;
        if (lim < 1 || lim > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        // 强制国家维度 + 前缀匹配，避免全球/前导模糊全表扫
        if (!StringUtils.hasText(countryCode) || countryCode.trim().length() != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = countryCode.trim().toUpperCase();
        List<GeoRegion> hits = geoDataCache.search(kw, code, level, lim);
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, GeoRegion> ancestorCache = new HashMap<>();
        List<RegionSearchVO> result = new ArrayList<>();
        for (GeoRegion hit : hits) {
            RegionSearchVO vo = toRegionSearchVO(hit, lang);
            vo.setFullPathName(buildFullPathName(hit, lang, ancestorCache));
            result.add(vo);
        }
        log.info("search end, size={}", result.size());
        return result;
    }

    /**
     * 按物化 path 组装命中节点的全路径展示名（lang 选取各层名称）。
     *
     * @param hit           命中区划
     * @param lang          语言偏好
     * @param ancestorCache 祖先实体缓存，跨多次命中复用，减少批量查库
     * @return 以 / 连接的全路径名称
     */
    private String buildFullPathName(GeoRegion hit, String lang, Map<Long, GeoRegion> ancestorCache) {
        List<Long> ids = PathUtil.parsePathIds(hit.getPath());
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (!ancestorCache.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            for (GeoRegion r : geoDataCache.listByIds(missing)) {
                ancestorCache.put(r.getId(), r);
            }
        }
        List<String> names = new ArrayList<>();
        for (Long id : ids) {
            GeoRegion r = ancestorCache.get(id);
            if (r != null) {
                names.add(LangUtil.resolveDisplayName(lang, r.getName(), r.getNameEn(), r.getNameCh()));
            }
        }
        return String.join("/", names);
    }

    /**
     * 将扁平节点列表组装为以 rootId 为根的树。
     *
     * @param nodes  扁平节点（含根）
     * @param rootId 树根 ID
     * @param lang   语言偏好
     * @return 树根 VO；若根不在列表中则返回 null
     */
    private RegionTreeVO buildTree(List<GeoRegion> nodes, Long rootId, String lang) {
        Map<Long, RegionTreeVO> map = new LinkedHashMap<>();
        for (GeoRegion node : nodes) {
            map.put(node.getId(), toRegionTreeVO(node, lang));
        }
        RegionTreeVO root = null;
        for (GeoRegion node : nodes) {
            RegionTreeVO vo = map.get(node.getId());
            if (Objects.equals(node.getId(), rootId)) {
                root = vo;
                continue;
            }
            RegionTreeVO parent = map.get(node.getParentId());
            if (parent != null) {
                parent.getChildren().add(vo);
            }
        }
        // isLeaf 与 listChildren 对齐：以本次树内是否挂子节点为准，不用 DB is_leaf 双路径
        for (RegionTreeVO vo : map.values()) {
            List<RegionTreeVO> children = vo.getChildren();
            vo.setIsLeaf(children == null || children.isEmpty());
        }
        return root;
    }

    /**
     * 国家实体转 CountryVO，并按 lang 填充 displayName。
     *
     * @param c    国家实体
     * @param lang 语言偏好
     * @return 国家视图对象
     */
    private CountryVO toCountryVO(GeoCountry c, String lang) {
        CountryVO vo = new CountryVO();
        vo.setId(c.getId());
        vo.setIso2(c.getIso2());
        vo.setIso3(c.getIso3());
        vo.setName(c.getName());
        vo.setNameEn(c.getNameEn());
        vo.setNameCh(c.getNameCh());
        vo.setDisplayName(LangUtil.resolveDisplayName(lang, c.getName(), c.getNameEn(), c.getNameCh()));
        vo.setIconBase64(c.getIconBase64());
        vo.setPhoneCode(c.getPhoneCode());
        vo.setMaxLevel(c.getMaxLevel());
        return vo;
    }

    /**
     * 区划实体转平级 RegionVO。
     *
     * @param r    区划实体
     * @param lang 语言偏好
     * @return 区划视图对象
     */
    private RegionVO toRegionVO(GeoRegion r, String lang) {
        RegionVO vo = new RegionVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    /**
     * 区划实体转树节点 RegionTreeVO。
     *
     * @param r    区划实体
     * @param lang 语言偏好
     * @return 树节点视图对象
     */
    private RegionTreeVO toRegionTreeVO(GeoRegion r, String lang) {
        RegionTreeVO vo = new RegionTreeVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    /**
     * 区划实体转搜索结果 RegionSearchVO（fullPathName 由调用方另填）。
     *
     * @param r    区划实体
     * @param lang 语言偏好
     * @return 搜索结果视图对象
     */
    private RegionSearchVO toRegionSearchVO(GeoRegion r, String lang) {
        RegionSearchVO vo = new RegionSearchVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    /**
     * 将区划实体公共字段填入 VO（含 displayName / isLeaf）。
     *
     * @param vo   目标 VO
     * @param r    源实体
     * @param lang 语言偏好
     */
    private void fillRegion(RegionVO vo, GeoRegion r, String lang) {
        vo.setId(r.getId());
        vo.setParentId(r.getParentId());
        vo.setCountryCode(r.getCountryCode());
        vo.setCode(r.getCode());
        vo.setName(r.getName());
        vo.setNameEn(r.getNameEn());
        vo.setNameCh(r.getNameCh());
        vo.setDisplayName(LangUtil.resolveDisplayName(lang, r.getName(), r.getNameEn(), r.getNameCh()));
        vo.setLevel(r.getLevel());
        vo.setRegionType(r.getRegionType());
        vo.setPath(r.getPath());
        // path/search：用库内 is_leaf；listChildren/tree 在组装后按实际子节点覆盖，避免双路径语义分叉
        vo.setIsLeaf(r.getIsLeaf() != null && r.getIsLeaf() == 1);
    }

    /**
     * 去掉首尾空白；空白串视为 null。
     *
     * @param value 原始字符串
     * @return trim 后非空串，或 null
     */
    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

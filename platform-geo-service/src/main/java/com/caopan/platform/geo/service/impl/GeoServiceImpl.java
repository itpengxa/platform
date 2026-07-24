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
 * 行政区划服务实现。实现 GeoService 接口的所有查询方法。
 * 关键能力：多语言 displayName 选取、树组装、祖先链解析、关键词搜索。
 * 所有查询走三级缓存（TieredCache），只读操作无事务开销。
 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);

    private final GeoDataCache geoDataCache;

    /**
     * 构造 GeoServiceImpl。
     * @param geoDataCache geoDataCache
     */
    public GeoServiceImpl(GeoDataCache geoDataCache) {
        this.geoDataCache = geoDataCache;
    }

    /**
     * 查询启用国家列表，支持语言与关键词过滤。
     * @param lang 语言偏好（local/en/zh，可空）
     * @param keyword 关键词，可空
     * @return 查询结果
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
     * @param parentId 父节点 ID
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
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
     * 统计childmap。
     * @param regions regions
     * @return 查询结果
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
     * @return 树根节点
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
        if (current == null && !regions.isEmpty()) {
            current = regions.get(regions.size() - 1);
        }
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
     * @param keyword 关键词，可空
     * @param countryCode 国家 ISO2 编码
     * @param level 层级过滤，可空
     * @param limit 返回条数上限
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
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
     * 构建full路径name。
     * @param hit hit
     * @param lang 语言偏好（local/en/zh，可空）
     * @param ancestorCache ancestorCache
     * @return 查询结果
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
     * 构建树。
     * @param nodes nodes
     * @param rootId 区划 ID
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
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
        return root;
    }

    /**
     * 转换为countryvo。
     * @param c c
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
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
     * 转换为regionvo。
     * @param r r
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
     */
    private RegionVO toRegionVO(GeoRegion r, String lang) {
        RegionVO vo = new RegionVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    /**
     * 转换为region树vo。
     * @param r r
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
     */
    private RegionTreeVO toRegionTreeVO(GeoRegion r, String lang) {
        RegionTreeVO vo = new RegionTreeVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    /**
     * 转换为region搜索vo。
     * @param r r
     * @param lang 语言偏好（local/en/zh，可空）
     * @return 查询结果
     */
    private RegionSearchVO toRegionSearchVO(GeoRegion r, String lang) {
        RegionSearchVO vo = new RegionSearchVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    /**
     * 填充region。
     * @param vo vo
     * @param r r
     * @param lang 语言偏好（local/en/zh，可空）
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
        vo.setIsLeaf(r.getIsLeaf() != null && r.getIsLeaf() == 1);
    }

    /**
     * 裁剪转换为null。
     * @param value value
     * @return 查询结果
     */
    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

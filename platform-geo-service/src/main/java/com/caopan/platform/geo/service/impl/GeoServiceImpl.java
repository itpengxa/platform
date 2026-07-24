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
 * 2026-07-24 GEO-001 行政区划查询实现（缓存原始数据，按 lang 组装 displayName）
 */
@Service
/**
 * 行政区划服务实现。实现 GeoService 接口的所有查询方法。
 * 关键能力：多语言 displayName 选取、树组装、祖先链解析、关键词搜索。
 * 所有查询走三级缓存（TieredCache），只读操作无事务开销。
 */
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);

    private final GeoDataCache geoDataCache;

    public GeoServiceImpl(GeoDataCache geoDataCache) {
        this.geoDataCache = geoDataCache;
    }

    @Override
    public List<CountryVO> listCountries(String lang, String keyword) {
        log.info("listCountries start, lang={}, keyword={}", lang, keyword);
        String kw = trimToNull(keyword);
        List<GeoCountry> list = geoDataCache.listCountries(kw);
        List<CountryVO> result = new ArrayList<>(list.size());
        for (GeoCountry c : list) {
            result.add(toCountryVO(c, lang));
        }
        log.info("listCountries end, size={}", result.size());
        return result;
    }

    @Override
    public List<RegionVO> listChildren(Long parentId, String lang) {
        log.info("listChildren start, parentId={}, lang={}", parentId, lang);
        if (parentId == null || parentId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        // 校验父节点存在（缓存加载内也会校验，此处保证错误码一致）
        if (geoDataCache.findEnabledById(parentId) == null) {
            throw new BizException(ErrorCode.PARENT_NOT_FOUND);
        }
        List<GeoRegion> children = geoDataCache.listChildren(parentId);
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

    @Override
    public List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang) {
        log.info("search start, keyword={}, countryCode={}, level={}, limit={}, lang={}",
                keyword, countryCode, level, limit, lang);
        if (!StringUtils.hasText(keyword) || keyword.trim().length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        int lim = limit == null ? 20 : limit;
        if (lim < 1 || lim > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = StringUtils.hasText(countryCode) ? countryCode.trim().toUpperCase() : null;
        List<GeoRegion> hits = geoDataCache.search(keyword.trim(), code, level, lim);
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

    private RegionVO toRegionVO(GeoRegion r, String lang) {
        RegionVO vo = new RegionVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    private RegionTreeVO toRegionTreeVO(GeoRegion r, String lang) {
        RegionTreeVO vo = new RegionTreeVO();
        fillRegion(vo, r, lang);
        return vo;
    }

    private RegionSearchVO toRegionSearchVO(GeoRegion r, String lang) {
        RegionSearchVO vo = new RegionSearchVO();
        fillRegion(vo, r, lang);
        return vo;
    }

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

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

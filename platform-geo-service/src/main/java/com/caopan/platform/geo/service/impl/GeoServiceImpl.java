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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行政区划服务实现（GEO-001 / platform-geo-service）。
 * <p>实现 {@link GeoService}：多语言 displayName、树组装、祖先链、国家维度前缀搜索。
 * 数据访问经 {@link GeoDataCache} 走三级缓存（L1 Caffeine → L2 Redis → L3 DB），只读无事务。</p>
 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);

    private final GeoDataCache geoDataCache;

    public GeoServiceImpl(GeoDataCache geoDataCache) {
        this.geoDataCache = geoDataCache;
    }

    @Override
    public List<CountryVO> listCountries(String lang, String keyword) {
        log.debug("listCountries start, lang={}, keyword={}", lang, keyword);
        String kw = normalizeCountryKeyword(keyword);
        List<CountryVO> result = geoDataCache.listCountries(kw).stream()
                .map(c -> toCountryVO(c, lang))
                .toList();
        log.debug("listCountries end, size={}", result.size());
        return result;
    }

    @Override
    public List<RegionVO> listChildren(Long parentId, String lang) {
        log.debug("listChildren start, parentId={}, lang={}", parentId, lang);
        requirePositiveId(parentId);
        List<GeoRegion> children = geoDataCache.preferNextLevel(
                geoDataCache.findEnabledById(parentId),
                geoDataCache.listChildren(parentId));
        Map<Long, Integer> childCounts = countChildMap(children);
        List<RegionVO> result = children.stream()
                .map(r -> {
                    RegionVO vo = toRegionVO(r, lang);
                    vo.setIsLeaf(childCounts.getOrDefault(r.getId(), 0) == 0);
                    return vo;
                })
                .toList();
        log.debug("listChildren end, parentId={}, size={}", parentId, result.size());
        return result;
    }

    private Map<Long, Integer> countChildMap(List<GeoRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return Map.of();
        }
        return geoDataCache.countChildren(regions.stream().map(GeoRegion::getId).toList());
    }

    @Override
    public RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang) {
        log.debug("getTree start, countryCode={}, rootId={}, depth={}, lang={}", countryCode, rootId, depth, lang);
        GeoDataCache.TreeLoadResult loaded = geoDataCache.loadTreeNodes(countryCode, rootId, depth);
        RegionTreeVO tree = buildTree(loaded.nodes(), loaded.root().getId(), lang);
        if (tree == null) {
            tree = toRegionTreeVO(loaded.root(), lang);
        }
        log.debug("getTree end, countryCode={}, nodeCount={}", countryCode, loaded.nodes().size());
        return tree;
    }

    @Override
    public List<RegionVO> getPath(Long id, String lang) {
        log.debug("getPath start, id={}, lang={}", id, lang);
        requirePositiveId(id);
        Map<Long, GeoRegion> map = geoDataCache.listPathEntities(id).stream()
                .collect(Collectors.toMap(GeoRegion::getId, Function.identity(), (a, b) -> a, HashMap::new));
        GeoRegion current = map.get(id);
        if (current == null) {
            throw new BizException(ErrorCode.REGION_NOT_FOUND);
        }
        List<Long> ids = PathUtil.parsePathIds(current.getPath());
        if (ids.isEmpty()) {
            ids = List.of(current.getId());
        }
        List<RegionVO> path = ids.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(r -> toRegionVO(r, lang))
                .toList();
        // toList() 不可变；applyActualIsLeaf 需要可写列表
        path = new ArrayList<>(path);
        applyActualIsLeaf(path);
        log.debug("getPath end, id={}, depth={}", id, path.size());
        return path;
    }

    @Override
    public List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang) {
        log.debug("search start, keyword={}, countryCode={}, level={}, limit={}, lang={}",
                keyword, countryCode, level, limit, lang);
        String kw = requireSearchKeyword(keyword);
        int lim = resolveLimit(limit);
        String code = requireIso2(countryCode);
        List<GeoRegion> hits = geoDataCache.search(kw, code, level, lim);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, GeoRegion> ancestorCache = new HashMap<>();
        List<RegionSearchVO> result = new ArrayList<>(hits.size());
        for (GeoRegion hit : hits) {
            RegionSearchVO vo = toRegionSearchVO(hit, lang);
            vo.setFullPathName(buildFullPathName(hit, lang, ancestorCache));
            result.add(vo);
        }
        applyActualIsLeaf(result);
        log.debug("search end, size={}", result.size());
        return result;
    }

    private String buildFullPathName(GeoRegion hit, String lang, Map<Long, GeoRegion> ancestorCache) {
        List<Long> ids = PathUtil.parsePathIds(hit.getPath());
        List<Long> missing = ids.stream().filter(id -> !ancestorCache.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            geoDataCache.listByIds(missing).forEach(r -> ancestorCache.put(r.getId(), r));
        }
        return ids.stream()
                .map(ancestorCache::get)
                .filter(Objects::nonNull)
                .map(r -> LangUtil.resolveDisplayName(lang, r.getName(), r.getNameEn(), r.getNameCh()))
                .collect(Collectors.joining("/"));
    }

    private RegionTreeVO buildTree(List<GeoRegion> nodes, Long rootId, String lang) {
        Map<Long, RegionTreeVO> map = nodes.stream()
                .collect(Collectors.toMap(
                        GeoRegion::getId,
                        n -> toRegionTreeVO(n, lang),
                        (a, b) -> a,
                        LinkedHashMap::new));
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
        map.values().forEach(vo -> {
            List<RegionTreeVO> children = vo.getChildren();
            vo.setIsLeaf(children == null || children.isEmpty());
        });
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

    private void applyActualIsLeaf(List<? extends RegionVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        List<GeoRegion> probe = vos.stream()
                .filter(vo -> vo != null && vo.getId() != null)
                .map(vo -> {
                    GeoRegion r = new GeoRegion();
                    r.setId(vo.getId());
                    return r;
                })
                .toList();
        Map<Long, Integer> counts = countChildMap(probe);
        vos.stream()
                .filter(vo -> vo != null && vo.getId() != null)
                .forEach(vo -> vo.setIsLeaf(counts.getOrDefault(vo.getId(), 0) == 0));
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
        vo.setIsLeaf(Objects.equals(r.getIsLeaf(), 1));
        vo.setLatitude(r.getLatitude());
        vo.setLongitude(r.getLongitude());
    }

    private static void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private static String requireIso2(String countryCode) {
        if (!StringUtils.hasText(countryCode) || countryCode.trim().length() != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return countryCode.trim().toUpperCase();
    }

    private static String requireSearchKeyword(String keyword) {
        if (!StringUtils.hasText(keyword) || keyword.trim().length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String kw = keyword.trim();
        if (kw.length() < 2 || kw.indexOf('%') >= 0 || kw.indexOf('_') >= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return kw;
    }

    private static int resolveLimit(Integer limit) {
        int lim = limit == null ? 20 : limit;
        if (lim < 1 || lim > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return lim;
    }

    private static String normalizeCountryKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return null;
        }
        if (kw.length() > 64 || kw.indexOf('%') >= 0 || kw.indexOf('_') >= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return kw.length() == 2 ? kw.toUpperCase() : kw;
    }
}

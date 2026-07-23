package com.caopan.platform.geo.service.impl;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.common.util.LangUtil;
import com.caopan.platform.geo.entity.GeoCountry;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.mapper.GeoCountryMapper;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.caopan.platform.geo.service.GeoService;
import com.caopan.platform.geo.vo.CountryVO;
import com.caopan.platform.geo.vo.RegionSearchVO;
import com.caopan.platform.geo.vo.RegionTreeVO;
import com.caopan.platform.geo.vo.RegionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
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
 * 2026-07-23 GEO-001 行政区划查询实现
 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);

    private final GeoCountryMapper geoCountryMapper;
    private final GeoRegionMapper geoRegionMapper;

    public GeoServiceImpl(GeoCountryMapper geoCountryMapper, GeoRegionMapper geoRegionMapper) {
        this.geoCountryMapper = geoCountryMapper;
        this.geoRegionMapper = geoRegionMapper;
    }

    @Override
    @Cacheable(cacheNames = "geoCountries", key = "#lang + ':' + (#keyword == null ? '' : #keyword)")
    public List<CountryVO> listCountries(String lang, String keyword) {
        log.info("listCountries start, lang={}, keyword={}", lang, keyword);
        String kw = trimToNull(keyword);
        List<GeoCountry> list = geoCountryMapper.listEnabled(kw);
        List<CountryVO> result = list.stream().map(c -> toCountryVO(c, lang)).toList();
        log.info("listCountries end, size={}", result.size());
        return result;
    }

    @Override
    @Cacheable(cacheNames = "geoChildren", key = "#parentId + ':' + #lang")
    public List<RegionVO> listChildren(Long parentId, String lang) {
        log.info("listChildren start, parentId={}, lang={}", parentId, lang);
        if (parentId == null || parentId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
        if (parent == null) {
            log.warn("listChildren parent missing, parentId={}", parentId);
            throw new BizException(ErrorCode.PARENT_NOT_FOUND);
        }
        List<GeoRegion> children = geoRegionMapper.listByParentId(parentId);
        List<RegionVO> result = children.stream().map(r -> toRegionVO(r, lang)).toList();
        log.info("listChildren end, parentId={}, size={}", parentId, result.size());
        return result;
    }

    @Override
    @Cacheable(cacheNames = "geoTree",
            key = "#countryCode + ':' + (#rootId == null ? 0 : #rootId) + ':' + (#depth == null ? 0 : #depth) + ':' + #lang")
    public RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang) {
        log.info("getTree start, countryCode={}, rootId={}, depth={}, lang={}", countryCode, rootId, depth, lang);
        if (!StringUtils.hasText(countryCode) || countryCode.trim().length() != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = countryCode.trim().toUpperCase();
        GeoRegion root;
        if (rootId != null && rootId > 0) {
            root = geoRegionMapper.findEnabledById(rootId);
            if (root == null || !code.equalsIgnoreCase(root.getCountryCode())) {
                throw new BizException(ErrorCode.REGION_NOT_FOUND);
            }
        } else {
            root = geoRegionMapper.findCountryByCode(code);
            if (root == null) {
                log.warn("getTree country missing, countryCode={}", code);
                throw new BizException(ErrorCode.COUNTRY_NOT_FOUND);
            }
        }
        Integer maxLevel = null;
        if (depth != null) {
            if (depth < 1 || depth > 5) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            maxLevel = root.getLevel() + depth - 1;
        }
        String pathPrefix = root.getPath();
        List<GeoRegion> nodes = geoRegionMapper.listSubtree(pathPrefix, maxLevel);
        RegionTreeVO tree = buildTree(nodes, root.getId(), lang);
        if (tree == null) {
            tree = toRegionTreeVO(root, lang);
        }
        log.info("getTree end, countryCode={}, nodeCount={}", code, nodes.size());
        return tree;
    }

    @Override
    @Cacheable(cacheNames = "geoPath", key = "#id + ':' + #lang")
    public List<RegionVO> getPath(Long id, String lang) {
        log.info("getPath start, id={}, lang={}", id, lang);
        if (id == null || id <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        GeoRegion current = geoRegionMapper.findEnabledById(id);
        if (current == null) {
            throw new BizException(ErrorCode.REGION_NOT_FOUND);
        }
        List<Long> ids = parsePathIds(current.getPath());
        if (ids.isEmpty()) {
            return List.of(toRegionVO(current, lang));
        }
        List<GeoRegion> regions = geoRegionMapper.listByIds(ids);
        Map<Long, GeoRegion> map = new HashMap<>();
        for (GeoRegion r : regions) {
            map.put(r.getId(), r);
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
        List<GeoRegion> hits = geoRegionMapper.search(keyword.trim(), code, level, lim);
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
        List<Long> ids = parsePathIds(hit.getPath());
        List<Long> missing = ids.stream().filter(id -> !ancestorCache.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            List<GeoRegion> loaded = geoRegionMapper.listByIds(missing);
            for (GeoRegion r : loaded) {
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

    private List<Long> parsePathIds(String path) {
        if (!StringUtils.hasText(path)) {
            return Collections.emptyList();
        }
        String[] parts = path.split("/");
        List<Long> ids = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                log.warn("parsePathIds skip invalid segment, path={}, part={}", path, part);
            }
        }
        return ids;
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

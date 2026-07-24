package com.caopan.platform.geo.service.support;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.cache.GeoCacheKeys;
import com.caopan.platform.geo.cache.TieredCache;
import com.caopan.platform.geo.entity.GeoCountry;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.mapper.GeoCountryMapper;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-07-24 GEO-001 原始数据三级缓存接入（Key 不含 lang）
 */
@Component
/**
 * 地理数据缓存门面。封装 TieredCache 的 geo 模块特定调用。
 * 处理缓存 miss 时的 DB 回查、序列化/反序列化、空值保护等。
 */
public class GeoDataCache {

    private static final Logger log = LoggerFactory.getLogger(GeoDataCache.class);

    private static final TypeReference<List<GeoCountry>> COUNTRY_LIST =
            new TypeReference<List<GeoCountry>>() {};
    private static final TypeReference<List<GeoRegion>> REGION_LIST =
            new TypeReference<List<GeoRegion>>() {};
    private static final TypeReference<TreeLoadResult> TREE_RESULT =
            new TypeReference<TreeLoadResult>() {};

    private final GeoCountryMapper geoCountryMapper;
    private final GeoRegionMapper geoRegionMapper;
    private final TieredCache tieredCache;

    public GeoDataCache(GeoCountryMapper geoCountryMapper,
                        GeoRegionMapper geoRegionMapper,
                        TieredCache tieredCache) {
        this.geoCountryMapper = geoCountryMapper;
        this.geoRegionMapper = geoRegionMapper;
        this.tieredCache = tieredCache;
    }

    public List<GeoCountry> listCountries(String keyword) {
        String key = GeoCacheKeys.countries(keyword);
        List<GeoCountry> list = tieredCache.get(key, COUNTRY_LIST, GeoCacheKeys.L2_COUNTRIES_TTL, () -> {
            log.info("L3 load countries, keyword={}", keyword);
            List<GeoCountry> rows = geoCountryMapper.listEnabled(keyword);
            return rows == null ? Collections.emptyList() : rows;
        });
        return list == null ? Collections.emptyList() : list;
    }

    public List<GeoRegion> listChildren(Long parentId) {
        String key = GeoCacheKeys.children(parentId);
        List<GeoRegion> list = tieredCache.get(key, REGION_LIST, GeoCacheKeys.L2_CHILDREN_TTL, () -> {
            log.info("L3 load children, parentId={}", parentId);
            GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
            if (parent == null) {
                throw new BizException(ErrorCode.PARENT_NOT_FOUND);
            }
            List<GeoRegion> children = geoRegionMapper.listByParentId(parentId);
            return children == null ? Collections.emptyList() : children;
        });
        return list == null ? Collections.emptyList() : list;
    }

    public List<GeoRegion> listPathEntities(Long id) {
        String key = GeoCacheKeys.path(id);
        List<GeoRegion> list = tieredCache.get(key, REGION_LIST, GeoCacheKeys.L2_PATH_TTL, () -> {
            log.info("L3 load path entities, id={}", id);
            GeoRegion current = geoRegionMapper.findEnabledById(id);
            if (current == null) {
                throw new BizException(ErrorCode.REGION_NOT_FOUND);
            }
            List<Long> ids = PathUtil.parsePathIds(current.getPath());
            if (ids.isEmpty()) {
                return Collections.singletonList(current);
            }
            List<GeoRegion> regions = geoRegionMapper.listByIds(ids);
            return regions == null ? Collections.emptyList() : regions;
        });
        return list == null ? Collections.emptyList() : list;
    }

    public TreeLoadResult loadTreeNodes(String countryCode, Long rootId, Integer depth) {
        if (!StringUtils.hasText(countryCode) || countryCode.trim().length() != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = countryCode.trim().toUpperCase();
        String key = GeoCacheKeys.tree(code, rootId, depth);
        TreeLoadResult result = tieredCache.get(key, TREE_RESULT, GeoCacheKeys.L2_TREE_TTL, () -> {
            log.info("L3 load tree nodes, countryCode={}, rootId={}, depth={}", code, rootId, depth);
            GeoRegion root;
            if (rootId != null && rootId > 0) {
                root = geoRegionMapper.findEnabledById(rootId);
                if (root == null || !code.equalsIgnoreCase(root.getCountryCode())) {
                    throw new BizException(ErrorCode.REGION_NOT_FOUND);
                }
            } else {
                root = geoRegionMapper.findCountryByCode(code);
                if (root == null) {
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
            List<GeoRegion> nodes = geoRegionMapper.listSubtree(root.getPath(), maxLevel);
            return new TreeLoadResult(root, nodes == null ? Collections.emptyList() : nodes);
        });
        if (result == null) {
            throw new BizException(ErrorCode.COUNTRY_NOT_FOUND);
        }
        return result;
    }

    public List<GeoRegion> search(String keyword, String countryCode, Integer level, int limit) {
        List<GeoRegion> hits = geoRegionMapper.search(keyword, countryCode, level, limit);
        return hits == null ? Collections.emptyList() : hits;
    }

    public List<GeoRegion> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<GeoRegion> list = geoRegionMapper.listByIds(ids);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 动态统计是否有子节点（isLeaf 以实际子级为准，不写死层级）
     */
    public Map<Long, Integer> countChildren(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = geoRegionMapper.countChildrenByParentIds(parentIds);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object pid = row.get("parentId");
            Object cnt = row.get("cnt");
            if (pid == null || cnt == null) {
                continue;
            }
            map.put(((Number) pid).longValue(), ((Number) cnt).intValue());
        }
        return map;
    }

    /**
     * 级联下钻：优先返回 parent.level+1；若无下一级则退回全部直属子节点（兼容扁平挂载）
     */
    public List<GeoRegion> preferNextLevel(GeoRegion parent, List<GeoRegion> all) {
        if (parent == null || parent.getLevel() == null || all == null || all.isEmpty()) {
            return all == null ? Collections.emptyList() : all;
        }
        int next = parent.getLevel() + 1;
        List<GeoRegion> filtered = new ArrayList<>();
        for (GeoRegion r : all) {
            if (r.getLevel() != null && r.getLevel() == next) {
                filtered.add(r);
            }
        }
        return filtered.isEmpty() ? all : filtered;
    }

    public GeoRegion findEnabledById(Long id) {
        return geoRegionMapper.findEnabledById(id);
    }

    public static final class TreeLoadResult implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private GeoRegion root;
        private List<GeoRegion> nodes;

        public TreeLoadResult() {
        }

        public TreeLoadResult(GeoRegion root, List<GeoRegion> nodes) {
            this.root = root;
            this.nodes = nodes;
        }

        public GeoRegion getRoot() {
            return root;
        }

        public void setRoot(GeoRegion root) {
            this.root = root;
        }

        public List<GeoRegion> getNodes() {
            return nodes;
        }

        public void setNodes(List<GeoRegion> nodes) {
            this.nodes = nodes;
        }
    }
}

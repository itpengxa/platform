package com.caopan.platform.geo.service.support;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.cache.GeoCacheKeys;
import com.caopan.platform.geo.cache.GeoCacheProperties;
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
 * 地理数据缓存门面。封装 TieredCache 的 geo 模块特定调用。
 */
@Component
public class GeoDataCache {

    private static final Logger log = LoggerFactory.getLogger(GeoDataCache.class);

    private static final TypeReference<List<GeoCountry>> COUNTRY_LIST =
            new TypeReference<List<GeoCountry>>() {};
    private static final TypeReference<List<GeoRegion>> REGION_LIST =
            new TypeReference<List<GeoRegion>>() {};
    private static final TypeReference<GeoRegion> REGION =
            new TypeReference<GeoRegion>() {};
    private static final TypeReference<TreeLoadResult> TREE_RESULT =
            new TypeReference<TreeLoadResult>() {};

    private final GeoCountryMapper geoCountryMapper;
    private final GeoRegionMapper geoRegionMapper;
    private final TieredCache tieredCache;
    private final GeoCacheProperties cacheProperties;

    public GeoDataCache(GeoCountryMapper geoCountryMapper,
                        GeoRegionMapper geoRegionMapper,
                        TieredCache tieredCache,
                        GeoCacheProperties cacheProperties) {
        this.geoCountryMapper = geoCountryMapper;
        this.geoRegionMapper = geoRegionMapper;
        this.tieredCache = tieredCache;
        this.cacheProperties = cacheProperties;
    }

    /**
     * 查询启用国家列表。
     */
    public List<GeoCountry> listCountries(String keyword) {
        String key = GeoCacheKeys.countries(keyword);
        List<GeoCountry> list = tieredCache.get(key, COUNTRY_LIST, cacheProperties.countriesTtl(), () -> {
            log.info("L3 load countries, keyword={}", keyword);
            List<GeoCountry> rows = geoCountryMapper.listEnabled(keyword);
            return rows == null ? Collections.emptyList() : rows;
        });
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 按父节点查询直属子区划；父不存在抛 {@link ErrorCode#PARENT_NOT_FOUND}。
     * 加载时回填父节点 region 缓存，避免 Service 层重复打库。
     */
    public List<GeoRegion> listChildren(Long parentId) {
        String key = GeoCacheKeys.children(parentId);
        List<GeoRegion> list = tieredCache.get(key, REGION_LIST, cacheProperties.childrenTtl(), () -> {
            log.info("L3 load children, parentId={}", parentId);
            GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
            if (parent == null) {
                tieredCache.putNegative(GeoCacheKeys.region(parentId));
                tieredCache.putNegative(key);
                throw new BizException(ErrorCode.PARENT_NOT_FOUND);
            }
            tieredCache.put(GeoCacheKeys.region(parentId), parent, cacheProperties.regionTtl());
            List<GeoRegion> children = geoRegionMapper.listByParentId(parentId);
            return children == null ? Collections.emptyList() : children;
        });
        // null = 负缓存（父不存在）
        if (list == null) {
            throw new BizException(ErrorCode.PARENT_NOT_FOUND);
        }
        return list;
    }

    /**
     * 祖先链实体列表。
     */
    public List<GeoRegion> listPathEntities(Long id) {
        String key = GeoCacheKeys.path(id);
        List<GeoRegion> list = tieredCache.get(key, REGION_LIST, cacheProperties.pathTtl(), () -> {
            log.info("L3 load path entities, id={}", id);
            GeoRegion current = geoRegionMapper.findEnabledById(id);
            if (current == null) {
                tieredCache.putNegative(GeoCacheKeys.region(id));
                tieredCache.putNegative(key);
                throw new BizException(ErrorCode.REGION_NOT_FOUND);
            }
            tieredCache.put(GeoCacheKeys.region(id), current, cacheProperties.regionTtl());
            List<Long> ids = PathUtil.parsePathIds(current.getPath());
            if (ids.isEmpty()) {
                return Collections.singletonList(current);
            }
            List<GeoRegion> regions = geoRegionMapper.listByIds(ids);
            return regions == null ? Collections.emptyList() : regions;
        });
        if (list == null) {
            throw new BizException(ErrorCode.REGION_NOT_FOUND);
        }
        return list;
    }

    /**
     * 加载树节点（depth 缺省由调用方归一）。
     */
    public TreeLoadResult loadTreeNodes(String countryCode, Long rootId, Integer depth) {
        if (!StringUtils.hasText(countryCode) || countryCode.trim().length() != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = countryCode.trim().toUpperCase();
        int depthRaw = depth == null ? 3 : depth;
        if (depthRaw < 1 || depthRaw > 5) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        // 未指定 rootId 时按国家级树处理，depth>3 直接封顶，避免错误缓存键
        boolean countryRoot = rootId == null || rootId <= 0;
        final int effectiveDepth = (countryRoot && depthRaw > 3) ? 3 : depthRaw;
        String key = GeoCacheKeys.tree(code, rootId, effectiveDepth);
        TreeLoadResult result = tieredCache.get(key, TREE_RESULT, cacheProperties.treeTtl(), () -> {
            log.info("L3 load tree nodes, countryCode={}, rootId={}, depth={}", code, rootId, effectiveDepth);
            GeoRegion root;
            if (!countryRoot) {
                root = geoRegionMapper.findEnabledById(rootId);
                if (root == null || !code.equalsIgnoreCase(root.getCountryCode())) {
                    tieredCache.putNegative(key);
                    throw new BizException(ErrorCode.REGION_NOT_FOUND);
                }
            } else {
                root = geoRegionMapper.findCountryByCode(code);
                if (root == null) {
                    tieredCache.putNegative(key);
                    throw new BizException(ErrorCode.COUNTRY_NOT_FOUND);
                }
            }
            int depthUse = effectiveDepth;
            if (root.getLevel() != null && root.getLevel() == 1 && depthUse > 3) {
                depthUse = 3;
            }
            tieredCache.put(GeoCacheKeys.region(root.getId()), root, cacheProperties.regionTtl());
            int maxLevel = root.getLevel() + depthUse - 1;
            int maxRows = cacheProperties.getTreeMaxRows();
            List<GeoRegion> nodes = geoRegionMapper.listSubtree(root.getPath(), maxLevel, maxRows);
            if (nodes != null && nodes.size() >= maxRows) {
                log.warn("tree result hit maxRows={}, countryCode={}, rootId={}, depth={}",
                        maxRows, code, rootId, depthUse);
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            return new TreeLoadResult(root, nodes == null ? Collections.emptyList() : nodes);
        });
        if (result == null) {
            throw new BizException(countryRoot ? ErrorCode.COUNTRY_NOT_FOUND : ErrorCode.REGION_NOT_FOUND);
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

    /**
     * 按主键查启用区划（走三级缓存）。
     */
    public GeoRegion findEnabledById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        return tieredCache.get(GeoCacheKeys.region(id), REGION, cacheProperties.regionTtl(), () -> {
            log.info("L3 load region, id={}", id);
            return geoRegionMapper.findEnabledById(id);
        });
    }

    public static final class TreeLoadResult implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        /** 树根节点 */
        private GeoRegion root;
        /** 子树扁平节点列表（含根） */
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

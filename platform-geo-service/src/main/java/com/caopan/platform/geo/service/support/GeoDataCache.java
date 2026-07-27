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
 * 地理数据缓存门面（GEO-001 / platform-geo-service）。
 * <p>封装 {@link TieredCache} 的 geo 特定键与加载逻辑：国家列表、子级、祖先链、子树、单节点。
 * 读路径 L1 Caffeine → L2 Redis → L3 Mapper/DB；负缓存防穿透，L3 singleflight 防击穿。
 * 供 {@link com.caopan.platform.geo.service.impl.GeoServiceImpl} 调用。</p>
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

    /**
     * 注入依赖构造。
     *
     * @param geoCountryMapper 国家 Mapper
     * @param geoRegionMapper  区划 Mapper
     * @param tieredCache      三级缓存
     * @param cacheProperties  缓存 TTL/容量配置
     */
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
     * 查询启用国家列表（走三级缓存；SQL 默认不查 icon_base64）。
     *
     * @param keyword 关键词，可空（iso2 精确或名称前缀）
     * @return 国家实体列表，不会为 null
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
     * <p>加载时回填父节点 region 缓存，避免 Service 层重复打库。</p>
     *
     * @param parentId 父节点 ID
     * @return 子区划列表
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
     * 加载祖先链实体列表（按当前节点 path 批量查 ID）。
     *
     * @param id 当前区划 ID
     * @return 祖先链实体（含自身）；不存在抛 {@link ErrorCode#REGION_NOT_FOUND}
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
     * 加载子树节点（depth 缺省 3；国家级树 depth&gt;4 封顶为 4）。
     *
     * @param countryCode 国家 ISO2
     * @param rootId      根节点，可空（空=国家级）
     * @param depth       深度 1~5，可空
     * @return 根节点 + 扁平子树节点
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
        // 未指定 rootId 时按国家级树处理，depth>4 直接封顶，避免错误缓存键
        boolean countryRoot = rootId == null || rootId <= 0;
        final int effectiveDepth = (countryRoot && depthRaw > 4) ? 4 : depthRaw;
        String key = GeoCacheKeys.tree(code, rootId, effectiveDepth);
        TreeLoadResult result = tieredCache.get(key, TREE_RESULT, cacheProperties.treeTtl(), () -> {
            log.info("L3 load tree nodes, countryCode={}, rootId={}, depth={}", code, rootId, effectiveDepth);
            GeoRegion root;
            /*
             * 1) 定树根：
             * - 传了 rootId：按 ID 取启用节点，且必须属于本次 countryCode（防跨国串树 / 越权拼参）
             * - 未传 rootId：按 ISO2 找 level=1 的国家节点作为根
             * miss 写负缓存，避免错误 ID/错误国家码反复打 DB
             */
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
            /*
             * 2) 深度封顶（双保险）：
             * 入口已对「无 rootId 的国家级请求」把 effectiveDepth 压到 ≤4；
             * 这里再拦一层：即便调用方带了 rootId 但该节点实际仍是 level=1（国家），
             * depth=5 会一次拉到 L5 街镇，数据量过大，故国家级强制 ≤4（国→…→区县）。
             * 省/市节点下钻仍可用 depth=5。
             */
            int depthUse = effectiveDepth;
            if (root.getLevel() != null && root.getLevel() == 1 && depthUse >= 4) {
                depthUse = 4;
            }
            // 根节点顺便暖 region 缓存，后续 path/children 可少打一次库
            tieredCache.put(GeoCacheKeys.region(root.getId()), root, cacheProperties.regionTtl());
            /*
             * 3) 用「最大 level」表达深度，而不是递归 N 次：
             * depth=1 → 只要根；depth=4 且根为 L1 → maxLevel=4（国+省+市+区县）
             * SQL：path LIKE '根path%' AND level<=maxLevel，一次查出扁平列表再在内存组树
             */
            int maxLevel = root.getLevel() + depthUse - 1;
            /*
             * 4) 行数硬顶：LIMIT=maxRows 时「刚好拉满」视为可能截断，拒绝返回半棵树，
             * 逼调用方缩小 depth 或改用更小的 rootId 级联，避免 OOM / 超大 JSON。
             */
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

    /**
     * 国家维度前缀搜索（不走三级缓存，结果依赖关键词变化大）。
     *
     * @param keyword     关键词前缀
     * @param countryCode 国家 ISO2
     * @param level       层级过滤，可空
     * @param limit       条数上限
     * @return 命中列表，不会为 null
     */
    public List<GeoRegion> search(String keyword, String countryCode, Integer level, int limit) {
        List<GeoRegion> hits = geoRegionMapper.search(keyword, countryCode, level, limit);
        return hits == null ? Collections.emptyList() : hits;
    }

    /**
     * 按 ID 批量查询区划（直查 DB，供路径名组装补祖先）。
     *
     * @param ids 区划 ID 列表
     * @return 实体列表，不会为 null
     */
    public List<GeoRegion> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<GeoRegion> list = geoRegionMapper.listByIds(ids);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 批量统计各父节点下启用子节点数。
     *
     * @param parentIds 父节点 ID 列表
     * @return parentId → 子节点数
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
     * 优先返回「下一层级」子节点；若无下一层数据则回退全部直属子。
     *
     * @param parent 父节点
     * @param all    直属子列表
     * @return 过滤后的子列表
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

    /**
     * 按主键查启用区划（走三级缓存）。
     *
     * @param id 区划 ID
     * @return 实体；不存在或非法 ID 返回 null（负缓存命中亦为 null）
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

    /**
     * 子树加载结果：根节点 + 扁平节点列表（含根）。
     */
    public static final class TreeLoadResult implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        /** 树根节点 */
        private GeoRegion root;
        /** 子树扁平节点列表（含根） */
        private List<GeoRegion> nodes;

        /** Jackson / 序列化用无参构造 */
        public TreeLoadResult() {
        }

        /**
         * @param root  树根
         * @param nodes 扁平节点（含根）
         */
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

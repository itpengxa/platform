package com.caopan.platform.geo.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * geo 缓存可配置项（GEO-001 / platform-geo-service）。
 * <p>绑定前缀 {@code platform.geo.cache}：L1 容量/TTL、各业务 L2 TTL、
 * 雪崩抖动、负缓存 TTL、树查询 maxRows。由 bootstrap {@code CacheConfig} 启用。</p>
 */
@ConfigurationProperties(prefix = "platform.geo.cache")
public class GeoCacheProperties {

    /** 是否启用 Redis L2 */
    private boolean redisEnabled = true;
    /** L1 最大条目数 */
    private long l1MaximumSize = 10_000L;
    /** L1 过期分钟 */
    private long l1TtlMinutes = 10L;
    /** 国家列表 L2 小时 */
    private long countriesTtlHours = 24L;
    /** 子级列表 L2 小时 */
    private long childrenTtlHours = 24L;
    /** 祖先链 L2 小时 */
    private long pathTtlHours = 24L;
    /** 单节点 L2 小时 */
    private long regionTtlHours = 24L;
    /** 树 L2 小时 */
    private long treeTtlHours = 12L;
    /**
     * TTL 随机抖动上限（秒），实际 TTL = 基础 + [0, jitterSeconds]。
     * 用于避免大量 key 同时过期造成缓存雪崩。
     */
    private long jitterSeconds = 300L;
    /** 负缓存（DB miss）TTL 秒，防穿透 */
    private long negativeTtlSeconds = 30L;
    /**
     * 树查询最大行数，超出直接拒绝，防大包 OOM。
     * 联调/灌库验证可临时调大（如 50000）。
     */
    private int treeMaxRows = 3000;
    /**
     * 国家级根（level=1 / 未传 rootId）允许的最大 depth（1~5）。
     * 请求 depth 超过该值时封顶；省/市等非国家级根不受此限制。
     */
    private int treeCountryMaxDepth = 3;

    /**
     * @return L1 过期时间
     */
    public Duration l1Ttl() {
        return Duration.ofMinutes(Math.max(l1TtlMinutes, 1L));
    }

    /**
     * @return 国家列表 L2 TTL（含抖动）
     */
    public Duration countriesTtl() {
        return withJitter(Duration.ofHours(Math.max(countriesTtlHours, 1L)));
    }

    /**
     * @return 子级列表 L2 TTL（含抖动）
     */
    public Duration childrenTtl() {
        return withJitter(Duration.ofHours(Math.max(childrenTtlHours, 1L)));
    }

    /**
     * @return 祖先链 L2 TTL（含抖动）
     */
    public Duration pathTtl() {
        return withJitter(Duration.ofHours(Math.max(pathTtlHours, 1L)));
    }

    /**
     * @return 单节点 L2 TTL（含抖动）
     */
    public Duration regionTtl() {
        return withJitter(Duration.ofHours(Math.max(regionTtlHours, 1L)));
    }

    /**
     * @return 子树 L2 TTL（含抖动）
     */
    public Duration treeTtl() {
        return withJitter(Duration.ofHours(Math.max(treeTtlHours, 1L)));
    }

    /**
     * 基础 TTL 上叠加随机秒数，打散过期时间，缓解缓存雪崩。
     *
     * @param base 基础 TTL
     * @return 叠加抖动后的 TTL
     */
    public Duration withJitter(Duration base) {
        long jitter = Math.max(jitterSeconds, 0L);
        if (jitter == 0L || base == null || base.isZero() || base.isNegative()) {
            return base;
        }
        long add = ThreadLocalRandom.current().nextLong(jitter + 1);
        return base.plusSeconds(add);
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public long getL1MaximumSize() {
        return l1MaximumSize;
    }

    public void setL1MaximumSize(long l1MaximumSize) {
        this.l1MaximumSize = l1MaximumSize;
    }

    public long getL1TtlMinutes() {
        return l1TtlMinutes;
    }

    public void setL1TtlMinutes(long l1TtlMinutes) {
        this.l1TtlMinutes = l1TtlMinutes;
    }

    public long getCountriesTtlHours() {
        return countriesTtlHours;
    }

    public void setCountriesTtlHours(long countriesTtlHours) {
        this.countriesTtlHours = countriesTtlHours;
    }

    public long getChildrenTtlHours() {
        return childrenTtlHours;
    }

    public void setChildrenTtlHours(long childrenTtlHours) {
        this.childrenTtlHours = childrenTtlHours;
    }

    public long getPathTtlHours() {
        return pathTtlHours;
    }

    public void setPathTtlHours(long pathTtlHours) {
        this.pathTtlHours = pathTtlHours;
    }

    public long getRegionTtlHours() {
        return regionTtlHours;
    }

    public void setRegionTtlHours(long regionTtlHours) {
        this.regionTtlHours = regionTtlHours;
    }

    public long getTreeTtlHours() {
        return treeTtlHours;
    }

    public void setTreeTtlHours(long treeTtlHours) {
        this.treeTtlHours = treeTtlHours;
    }

    /**
     * @return 负缓存 TTL
     */
    public Duration negativeTtl() {
        return Duration.ofSeconds(Math.max(negativeTtlSeconds, 1L));
    }

    public long getJitterSeconds() {
        return jitterSeconds;
    }

    public void setJitterSeconds(long jitterSeconds) {
        this.jitterSeconds = jitterSeconds;
    }

    public long getNegativeTtlSeconds() {
        return negativeTtlSeconds;
    }

    public void setNegativeTtlSeconds(long negativeTtlSeconds) {
        this.negativeTtlSeconds = negativeTtlSeconds;
    }

    /**
     * @return 树查询最大行数（非法配置时回退 3000）
     */
    public int getTreeMaxRows() {
        return treeMaxRows <= 0 ? 3000 : treeMaxRows;
    }

    public void setTreeMaxRows(int treeMaxRows) {
        this.treeMaxRows = treeMaxRows;
    }

    /**
     * @return 国家级树最大 depth，钳制在 1~5；非法配置回退 4
     */
    public int getTreeCountryMaxDepth() {
        if (treeCountryMaxDepth < 1 || treeCountryMaxDepth > 5) {
            return 4;
        }
        return treeCountryMaxDepth;
    }

    public void setTreeCountryMaxDepth(int treeCountryMaxDepth) {
        this.treeCountryMaxDepth = treeCountryMaxDepth;
    }
}

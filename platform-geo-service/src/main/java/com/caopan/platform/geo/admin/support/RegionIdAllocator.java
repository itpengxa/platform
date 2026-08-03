package com.caopan.platform.geo.admin.support;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.mapper.GeoRegionIdSeqMapper;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import org.springframework.stereotype.Component;

/**
 * 区划 ID 号段分配（GEO-002）。
 * <p>L2=2亿+，L3=3亿+，L4=4亿+，L5=5亿+。
 * 通过 {@code geo_region_id_seq} 行级锁原子取号，避免并发下 {@code MAX(id)+1} 撞主键。</p>
 */
@Component
public class RegionIdAllocator {

    private static final long BASE_L2 = 200_000_000L;
    private static final long BASE_L3 = 300_000_000L;
    private static final long BASE_L4 = 400_000_000L;
    private static final long BASE_L5 = 500_000_000L;

    private final GeoRegionMapper geoRegionMapper;
    private final GeoRegionIdSeqMapper seqMapper;

    public RegionIdAllocator(GeoRegionMapper geoRegionMapper, GeoRegionIdSeqMapper seqMapper) {
        this.geoRegionMapper = geoRegionMapper;
        this.seqMapper = seqMapper;
    }

    /**
     * 为指定层级分配下一个 ID（须在事务内调用，与 insert 同事务）。
     *
     * @param level 目标层级 2~5
     * @return 新 ID
     */
    public long allocate(int level) {
        if (level < 2 || level > 5) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        int updated = seqMapper.bumpAndCapture(level);
        if (updated <= 0) {
            seedFromMax(level);
            updated = seqMapper.bumpAndCapture(level);
        }
        if (updated <= 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        Long next = seqMapper.lastInsertId();
        if (next == null || next <= 0L) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        return next;
    }

    /** 序列行缺失时按主表 MAX(id)+1 灌初值（迁移脚本未执行的兜底）。 */
    private void seedFromMax(int level) {
        Long floor = geoRegionMapper.nextIdForLevel(level);
        long nextId = floor == null ? baseForLevel(level) : floor;
        seqMapper.upsertFloor(level, nextId);
    }

    static long baseForLevel(int level) {
        return switch (level) {
            case 2 -> BASE_L2;
            case 3 -> BASE_L3;
            case 4 -> BASE_L4;
            case 5 -> BASE_L5;
            default -> throw new BizException(ErrorCode.PARAM_INVALID);
        };
    }
}

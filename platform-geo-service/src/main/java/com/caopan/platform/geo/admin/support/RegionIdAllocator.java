package com.caopan.platform.geo.admin.support;

import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import org.springframework.stereotype.Component;

/**
 * 区划 ID 号段分配（GEO-002）。
 * <p>L2=2亿+，L3=3亿+，L4=4亿+，L5=5亿+。</p>
 */
@Component
public class RegionIdAllocator {

    private static final long BASE_L2 = 200_000_000L;
    private static final long BASE_L3 = 300_000_000L;
    private static final long BASE_L4 = 400_000_000L;
    private static final long BASE_L5 = 500_000_000L;

    private final GeoRegionMapper geoRegionMapper;

    public RegionIdAllocator(GeoRegionMapper geoRegionMapper) {
        this.geoRegionMapper = geoRegionMapper;
    }

    /**
     * 为指定层级分配下一个 ID。
     *
     * @param level 目标层级 2~5
     * @return 新 ID
     */
    public long allocate(int level) {
        if (level < 2 || level > 5) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Long next = geoRegionMapper.nextIdForLevel(level);
        if (next == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        return next;
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

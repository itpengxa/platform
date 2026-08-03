package com.caopan.platform.geo.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 区划 ID 号段序列 Mapper。
 */
@Mapper
public interface GeoRegionIdSeqMapper {

    /**
     * 原子取号：将 next_id 写入 LAST_INSERT_ID 后自增，返回刚分配的 ID。
     *
     * @return 影响行数（0 表示该 level 尚未初始化）
     */
    int bumpAndCapture(@Param("level") int level);

    Long lastInsertId();

    /**
     * 若不存在则插入；已存在则把 next_id 抬到不低于 floorNextId。
     */
    int upsertFloor(@Param("level") int level, @Param("floorNextId") long floorNextId);
}

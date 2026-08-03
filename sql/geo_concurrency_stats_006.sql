-- GEO 并发安全 + 看板索引（可在已有库上单独执行）
-- 1) 区划 ID 号段序列（替代 MAX(id)+1 无锁分配）
-- 2) geo_region(level, id) 便于号段巡检 / 按层扫描
-- 3) 看板热点：按时间扫 region_id 更贴合 hot-regions 查询

CREATE TABLE IF NOT EXISTS geo_region_id_seq (
  level       TINYINT  NOT NULL COMMENT '区划层级 2-5',
  next_id     BIGINT   NOT NULL COMMENT '下一个待分配 ID',
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区划 ID 号段序列（行级锁分配）';

-- 按当前主表 MAX 灌初值；已有行则取 GREATEST，避免回拨
INSERT INTO geo_region_id_seq (level, next_id)
SELECT 2, COALESCE(MAX(id), 199999999) + 1 FROM geo_region WHERE level = 2
ON DUPLICATE KEY UPDATE next_id = GREATEST(next_id, VALUES(next_id));

INSERT INTO geo_region_id_seq (level, next_id)
SELECT 3, COALESCE(MAX(id), 299999999) + 1 FROM geo_region WHERE level = 3
ON DUPLICATE KEY UPDATE next_id = GREATEST(next_id, VALUES(next_id));

INSERT INTO geo_region_id_seq (level, next_id)
SELECT 4, COALESCE(MAX(id), 399999999) + 1 FROM geo_region WHERE level = 4
ON DUPLICATE KEY UPDATE next_id = GREATEST(next_id, VALUES(next_id));

INSERT INTO geo_region_id_seq (level, next_id)
SELECT 5, COALESCE(MAX(id), 499999999) + 1 FROM geo_region WHERE level = 5
ON DUPLICATE KEY UPDATE next_id = GREATEST(next_id, VALUES(next_id));

-- 按层查 MAX / 巡检号段
ALTER TABLE geo_region
  ADD KEY idx_level_id (level, id);

-- 热点区划：日范围 + region_id IS NOT NULL
ALTER TABLE platform_api_access_stat
  ADD KEY idx_stat_time_region (called_at, region_id);

-- GEO 经纬度近邻反查索引（性能）
-- 无索引：VN 街道 bbox ~230ms / 全球 ~2s+
-- 有索引：约 20~30ms
-- 用法：mysql -u ... platform < sql/geo_nearest_index_005.sql
-- 可重复执行：已存在则跳过

SET @db := DATABASE();

SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = 'geo_region' AND index_name = 'idx_nearest_lat_lon'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE geo_region ADD INDEX idx_nearest_lat_lon (latitude, longitude)',
  'SELECT ''idx_nearest_lat_lon already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = 'geo_region' AND index_name = 'idx_nearest_country_level_lat'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE geo_region ADD INDEX idx_nearest_country_level_lat (country_code, status, level, latitude)',
  'SELECT ''idx_nearest_country_level_lat already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

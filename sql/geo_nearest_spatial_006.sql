-- GEO 经纬度近邻：独立点表 + SPATIAL INDEX
-- MySQL 8：
--   ST_SRID(POINT(x,y),4326) 的 x=longitude, y=latitude
--   ST_GeomFromText('POINT(lat lon)',4326) / POLYGON WKT 为 lat lon 轴序
-- 用法：mysql -u ... platform < sql/geo_nearest_spatial_006.sql

CREATE TABLE IF NOT EXISTS geo_region_point (
  id        BIGINT NOT NULL COMMENT '对应 geo_region.id',
  location  POINT NOT NULL SRID 4326 COMMENT 'WGS84，ST_SRID(POINT(lon,lat),4326)',
  PRIMARY KEY (id),
  SPATIAL INDEX idx_location_spatial (location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='区划坐标点（空间索引专用）';

TRUNCATE TABLE geo_region_point;

INSERT INTO geo_region_point (id, location)
SELECT id, ST_SRID(POINT(longitude, latitude), 4326)
FROM geo_region
WHERE status = 1
  AND latitude IS NOT NULL
  AND longitude IS NOT NULL
  AND latitude BETWEEN -90 AND 90
  AND longitude BETWEEN -180 AND 180;

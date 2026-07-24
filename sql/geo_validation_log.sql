-- GEO-001 越南灌库校验记录表
USE platform;

CREATE TABLE IF NOT EXISTS geo_validation_log (
  id                      BIGINT       NOT NULL AUTO_INCREMENT,
  batch_id                VARCHAR(64)  NOT NULL COMMENT '批次号',
  country_code            CHAR(2)      NOT NULL,
  source                  VARCHAR(32)  NOT NULL COMMENT 'CSC/GEONAMES',
  source_id               VARCHAR(64)  DEFAULT NULL,
  region_temp_id          BIGINT       DEFAULT NULL COMMENT '清洗后临时 id',
  level                   TINYINT      DEFAULT NULL,
  input_name              VARCHAR(256) DEFAULT NULL,
  input_address           VARCHAR(512) DEFAULT NULL,
  input_lat               DECIMAL(10,7) DEFAULT NULL,
  input_lng               DECIMAL(10,7) DEFAULT NULL,
  google_status           VARCHAR(32)  DEFAULT NULL COMMENT 'OK/ZERO_RESULTS/...',
  google_formatted_address VARCHAR(512) DEFAULT NULL,
  google_lat              DECIMAL(10,7) DEFAULT NULL,
  google_lng              DECIMAL(10,7) DEFAULT NULL,
  google_place_id         VARCHAR(128) DEFAULT NULL,
  distance_meters         DOUBLE       DEFAULT NULL COMMENT '源坐标与 Google 坐标距离',
  address_ok              TINYINT      NOT NULL DEFAULT 0,
  latlng_ok               TINYINT      NOT NULL DEFAULT 0,
  overall_ok              TINYINT      NOT NULL DEFAULT 0,
  fail_reason             VARCHAR(512) DEFAULT NULL,
  raw_response            MEDIUMTEXT   DEFAULT NULL,
  created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_batch (batch_id),
  KEY idx_overall (country_code, overall_ok),
  KEY idx_source (source, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行政区划 Google 校验记录';

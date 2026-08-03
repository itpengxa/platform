-- GEO-002：管理端 / 上报 / Token 白名单 / 看板维度（可在已有库上单独执行）

-- 1) 接入方白名单字段
ALTER TABLE platform_access_client
  ADD COLUMN allow_issue TINYINT NOT NULL DEFAULT 1 COMMENT '1允许签发Token 0禁止' AFTER status,
  ADD COLUMN remark VARCHAR(256) DEFAULT NULL COMMENT '用途说明' AFTER allow_issue;

-- 存量视为允许签发（与 DEFAULT 一致；显式刷新便于审计）
UPDATE platform_access_client SET allow_issue = 1 WHERE allow_issue IS NULL;

-- 2) 调用明细增加区划维度（看板高频区划）
ALTER TABLE platform_api_access_stat
  ADD COLUMN country_code CHAR(2) DEFAULT NULL COMMENT '从入参解析的国家码' AFTER cost_ms,
  ADD COLUMN region_id BIGINT DEFAULT NULL COMMENT '被查节点/父节点ID' AFTER country_code,
  ADD COLUMN region_level TINYINT DEFAULT NULL COMMENT '层级1-5' AFTER region_id;

ALTER TABLE platform_api_access_stat
  ADD KEY idx_stat_region_time (called_at, country_code, region_id),
  ADD KEY idx_stat_level_time (called_at, region_level, country_code);

-- 3) 缺省上报记录
CREATE TABLE IF NOT EXISTS geo_region_report (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  client_code     VARCHAR(64)   NOT NULL COMMENT '上报方',
  parent_id       BIGINT        NOT NULL COMMENT '挂载父节点',
  country_code    CHAR(2)       NOT NULL COMMENT '国家ISO2',
  missing_name    VARCHAR(128)  NOT NULL COMMENT '缺省名称',
  missing_name_en VARCHAR(128)  DEFAULT NULL,
  missing_name_ch VARCHAR(128)  DEFAULT NULL,
  remark          VARCHAR(512)  DEFAULT NULL,
  geocode_lat     DECIMAL(10,7) DEFAULT NULL,
  geocode_lng     DECIMAL(10,7) DEFAULT NULL,
  distance_km     DECIMAL(10,3) DEFAULT NULL,
  result_status   VARCHAR(32)   NOT NULL COMMENT 'AUTO_CREATED/GEOCODE_FAIL/DISTANCE_REJECT/ALREADY_EXISTS/PARENT_NO_COORD/MANUAL_CREATED/REJECTED',
  region_id       BIGINT        DEFAULT NULL COMMENT '落库后的区划ID',
  geocode_raw     VARCHAR(2048) DEFAULT NULL COMMENT '地图返回摘要',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_parent_name (parent_id, missing_name),
  KEY idx_status_time (result_status, created_at),
  KEY idx_client_time (client_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='区划缺省上报记录';

-- platform geo module schema
-- GEO-001 | MySQL 8.0+
-- 仅业务查询表；数据由离线脚本灌入

CREATE DATABASE IF NOT EXISTS platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE platform;

-- ----------------------------
-- 国家扩展信息表
-- ----------------------------
DROP TABLE IF EXISTS geo_country;
CREATE TABLE geo_country (
  id            BIGINT       NOT NULL COMMENT '主键，与 geo_region 国家节点 id 一致',
  iso2          CHAR(2)      NOT NULL COMMENT 'ISO 3166-1 alpha-2',
  iso3          CHAR(3)      NOT NULL COMMENT 'ISO 3166-1 alpha-3',
  name          VARCHAR(128) NOT NULL COMMENT '本地名（缺省展示）',
  name_en       VARCHAR(128) DEFAULT NULL COMMENT '英文名',
  name_ch       VARCHAR(128) DEFAULT NULL COMMENT '中文名',
  icon_base64   MEDIUMTEXT   DEFAULT NULL COMMENT '国家图标 Base64',
  phone_code    VARCHAR(16)  DEFAULT NULL COMMENT '国际区号',
  currency_code CHAR(3)      DEFAULT NULL COMMENT '货币码',
  max_level     TINYINT      NOT NULL DEFAULT 3 COMMENT '该国最深层级 3/4/5',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  sort          INT          NOT NULL DEFAULT 0 COMMENT '排序',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iso2 (iso2),
  KEY idx_status_sort (status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国家扩展信息';

-- ----------------------------
-- 行政区划树表（含国家节点）
-- ----------------------------
DROP TABLE IF EXISTS geo_region;
CREATE TABLE geo_region (
  id            BIGINT        NOT NULL COMMENT '主键',
  parent_id     BIGINT        NOT NULL DEFAULT 0 COMMENT '父节点，国家为 0',
  country_code  CHAR(2)       NOT NULL COMMENT '所属国家 ISO2',
  name          VARCHAR(256)  NOT NULL COMMENT '本地名（缺省展示）',
  name_en       VARCHAR(256)  DEFAULT NULL COMMENT '英文名',
  name_ch       VARCHAR(256)  DEFAULT NULL COMMENT '中文名',
  code          VARCHAR(64)   DEFAULT NULL COMMENT '行政编码',
  level         TINYINT       NOT NULL COMMENT '1国家 2省州 3城市 4区县 5街道镇',
  region_type   VARCHAR(32)   NOT NULL COMMENT 'COUNTRY/PROVINCE/STATE/CITY/DISTRICT/COUNTY/STREET/TOWN',
  path          VARCHAR(256)  NOT NULL COMMENT '物化路径 /id/id/',
  is_leaf       TINYINT       NOT NULL DEFAULT 1 COMMENT '是否末级',
  latitude      DECIMAL(10,7) DEFAULT NULL,
  longitude     DECIMAL(10,7) DEFAULT NULL,
  source        VARCHAR(32)   DEFAULT NULL COMMENT '溯源 CSC/GEONAMES/CN_STATS',
  source_id     VARCHAR(64)   DEFAULT NULL COMMENT '来源原始 id',
  status        TINYINT       NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  sort          INT           NOT NULL DEFAULT 0,
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_parent (parent_id, status),
  KEY idx_country_level (country_code, level),
  KEY idx_code (code),
  KEY idx_name (name(64)),
  KEY idx_path (path(64)),
  KEY idx_nearest_lat_lon (latitude, longitude),
  KEY idx_nearest_country_level_lat (country_code, status, level, latitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行政区划树';

-- ----------------------------
-- 接入方 / 长效 Token / 调用统计（2026-07-27）
-- ----------------------------
DROP TABLE IF EXISTS platform_api_access_stat;
DROP TABLE IF EXISTS platform_access_token;
DROP TABLE IF EXISTS platform_access_client;

CREATE TABLE platform_access_client (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  client_code   VARCHAR(64)  NOT NULL COMMENT '接入方编码',
  client_name   VARCHAR(128) NOT NULL COMMENT '展示名',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_client_code (client_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 接入方';

CREATE TABLE platform_access_token (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  client_id     BIGINT       NOT NULL COMMENT '接入方 id',
  token_hash    CHAR(64)     NOT NULL COMMENT 'SHA-256(hex)',
  token_prefix  VARCHAR(8)   NOT NULL COMMENT '明文前缀（排障）',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1有效 0吊销',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_token_hash (token_hash),
  KEY idx_client_status (client_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 访问令牌（长效，再签发即吊销旧值）';

CREATE TABLE platform_api_access_stat (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  client_code     VARCHAR(64)   NOT NULL COMMENT '调用来源',
  called_at       DATETIME(3)   NOT NULL COMMENT '调用时间',
  api_key         VARCHAR(128)  NOT NULL COMMENT 'METHOD + 规范化路径',
  request_params  VARCHAR(2048) DEFAULT NULL COMMENT '入参快照（截断）',
  success         TINYINT       NOT NULL COMMENT '1成功 0失败（含 BizException）',
  error_type      VARCHAR(128)  DEFAULT NULL COMMENT '失败异常简名',
  cost_ms         INT           DEFAULT NULL COMMENT '耗时毫秒',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_called_client_api (called_at, client_code, api_key),
  KEY idx_client_day (client_code, called_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 调用记录（支撑日聚合统计）';

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
  KEY idx_path (path(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行政区划树';

-- GEO-001 增量：接入 Token + 调用统计（可在已有库上单独执行）
-- 无过期字段；再调 issue 吊销旧 Token

CREATE TABLE IF NOT EXISTS platform_access_client (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  client_code   VARCHAR(64)  NOT NULL COMMENT '接入方编码',
  client_name   VARCHAR(128) NOT NULL COMMENT '展示名',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_client_code (client_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 接入方';

CREATE TABLE IF NOT EXISTS platform_access_token (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 访问令牌';

CREATE TABLE IF NOT EXISTS platform_api_access_stat (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  client_code     VARCHAR(64)   NOT NULL COMMENT '调用来源',
  called_at       DATETIME(3)   NOT NULL COMMENT '调用时间',
  api_key         VARCHAR(128)  NOT NULL COMMENT 'METHOD + 规范化路径',
  request_params  VARCHAR(2048) DEFAULT NULL COMMENT '入参快照（截断）',
  success         TINYINT       NOT NULL COMMENT '1成功 0失败',
  error_type      VARCHAR(128)  DEFAULT NULL COMMENT '失败异常简名',
  cost_ms         INT           DEFAULT NULL COMMENT '耗时毫秒',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_called_client_api (called_at, client_code, api_key),
  KEY idx_client_day (client_code, called_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API 调用记录';

-- GEO 管理后台：统一操作审计日志（区划 / 配置 / 缓存等高风险写操作）

CREATE TABLE IF NOT EXISTS platform_admin_operation_log (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  module           VARCHAR(32)   NOT NULL COMMENT 'region/config/cache',
  action           VARCHAR(32)   NOT NULL COMMENT 'CREATE/UPDATE/STATUS/UPSERT/RESET/RELOAD/CLEAR/EVICT',
  resource_type    VARCHAR(64)   NOT NULL COMMENT 'geo_region / platform_runtime_config / geo_cache',
  resource_id      VARCHAR(128)  NULL COMMENT '业务主键或 config_key / scope',
  request_summary  VARCHAR(2048) NULL COMMENT '脱敏后入参摘要',
  before_data      TEXT          NULL COMMENT '可选变更前快照(JSON)',
  after_data       TEXT          NULL COMMENT '可选变更后快照(JSON)',
  success          TINYINT       NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  error_msg        VARCHAR(512)  NULL,
  operator         VARCHAR(64)   NULL COMMENT 'username / legacy-secret / unknown',
  operator_id      BIGINT        NULL,
  client_ip        VARCHAR(64)   NULL,
  cost_ms          INT           NULL,
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_module_time (module, created_at),
  KEY idx_operator_time (operator, created_at),
  KEY idx_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='管理端操作审计';

-- GEO 管理后台：运行时配置覆盖表（yml 默认 + DB 覆盖 + 热生效）

CREATE TABLE IF NOT EXISTS platform_runtime_config (
  config_key    VARCHAR(128) NOT NULL COMMENT '点分键，对齐 platform.geo.*',
  config_value  TEXT         NULL COMMENT '明文或 AES-GCM 密文(Base64)',
  value_type    VARCHAR(16)  NOT NULL COMMENT 'STRING/BOOL/INT/LONG/DOUBLE/SECRET',
  config_group  VARCHAR(32)  NOT NULL COMMENT 'report/rate-limit/auth/cache/access-log/admin',
  secret        TINYINT      NOT NULL DEFAULT 0 COMMENT '1=密钥项 API 脱敏',
  description   VARCHAR(256) NULL COMMENT '中文说明',
  updated_by    VARCHAR(64)  NULL COMMENT '最后修改人',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version       INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
  PRIMARY KEY (config_key),
  KEY idx_group (config_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行时配置覆盖';

CREATE TABLE IF NOT EXISTS platform_runtime_config_audit (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  config_key    VARCHAR(128) NOT NULL,
  old_value     TEXT         NULL COMMENT '脱敏后旧值',
  new_value     TEXT         NULL COMMENT '脱敏后新值',
  action        VARCHAR(16)  NOT NULL COMMENT 'UPSERT/RESET',
  updated_by    VARCHAR(64)  NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_key_time (config_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行时配置变更审计';

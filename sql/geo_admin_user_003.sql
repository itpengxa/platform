-- GEO-002 增量：管理员账号密码登录（替代 X-Admin-Secret）

CREATE TABLE IF NOT EXISTS platform_admin_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(64)  NOT NULL COMMENT '登录名',
  password_salt CHAR(32)     NOT NULL COMMENT '盐（hex）',
  password_hash CHAR(64)     NOT NULL COMMENT 'SHA-256(salt:password) hex',
  display_name  VARCHAR(128) NOT NULL COMMENT '展示名',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理端用户';

CREATE TABLE IF NOT EXISTS platform_admin_session (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id       BIGINT       NOT NULL COMMENT '管理员 id',
  token_hash    CHAR(64)     NOT NULL COMMENT '会话 Token SHA-256',
  token_prefix  VARCHAR(8)   NOT NULL COMMENT '明文前缀',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1有效 0吊销',
  expire_at     DATETIME     NOT NULL COMMENT '过期时间',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_token_hash (token_hash),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理端登录会话';

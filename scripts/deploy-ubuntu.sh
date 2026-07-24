#!/usr/bin/env bash
# ============================================================
# platform 底座 — Ubuntu 一键部署脚本
# 用法: chmod +x deploy.sh && sudo ./deploy.sh
# 支持 Ubuntu 20.04 / 22.04 / 24.04
# ============================================================
set -euo pipefail

# ---------- 配置（按需修改）----------
APP_USER="platform"
APP_DIR="/opt/platform"
APP_JAR="platform-bootstrap.jar"
MYSQL_ROOT_PASS="root123"
MYSQL_DB="platform"
MYSQL_USER="platform"
MYSQL_PASS="platform@2024"
REDIS_PORT=6379
APP_PORT=8088
JAR_DOWNLOAD_URL=""  # 留空则跳过下载，需手动放 jar 到脚本同目录

# ---------- 颜色 ----------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log() { echo -e "${GREEN}[$(date +'%H:%M:%S')]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ---------- 检查 root ----------
[[ $EUID -ne 0 ]] && err "请用 sudo 执行: sudo ./deploy.sh"

# ---------- 检查系统 ----------
if ! grep -qi ubuntu /etc/os-release 2>/dev/null; then
    warn "本脚本针对 Ubuntu 设计，当前系统可能不兼容"
fi

log "=========================================="
log " platform 底座 — Ubuntu 一键部署"
log "=========================================="

# ========== 1. 系统依赖 ==========
log "[1/7] 更新系统包..."
apt-get update -qq

log "[1/7] 安装基础依赖..."
apt-get install -y -qq curl wget gnupg software-properties-common ca-certificates \
    lsb-release unzip git 2>/dev/null

# ========== 2. JDK 21 ==========
log "[2/7] 安装 JDK 21..."
if java -version 2>&1 | grep -q "21\."; then
    log "  JDK 21 已安装，跳过"
else
    # 尝试 apt 安装
    if apt-get install -y -qq openjdk-21-jdk-headless 2>/dev/null; then
        log "  通过 apt 安装 JDK 21 成功"
    else
        # 手动下载
        log "  通过手动下载安装 JDK 21..."
        JDK_URL="https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz"
        wget -q "$JDK_URL" -O /tmp/jdk21.tar.gz
        tar -xzf /tmp/jdk21.tar.gz -C /usr/local/
        JDK_DIR=$(ls -d /usr/local/jdk-21* 2>/dev/null | head -1)
        [[ -z "$JDK_DIR" ]] && err "JDK 解压失败"
        update-alternatives --install /usr/bin/java java "$JDK_DIR/bin/java" 2100
        update-alternatives --install /usr/bin/javac javac "$JDK_DIR/bin/javac" 2100
        echo "export JAVA_HOME=$JDK_DIR" > /etc/profile.d/jdk.sh
        echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile.d/jdk.sh
        chmod +x /etc/profile.d/jdk.sh
    fi
fi
java -version 2>&1 | head -1 || err "JDK 安装失败"

# ========== 3. MySQL 8.0 ==========
log "[3/7] 安装 MySQL 8.0..."
if command -v mysql &>/dev/null; then
    log "  MySQL 已安装，跳过"
else
    # Ubuntu 24.04 默认仓库有 MySQL 8.0/8.4
    if apt-get install -y -qq mysql-server 2>/dev/null; then
        log "  通过 apt 安装 MySQL 成功"
    else
        # 从 MySQL 官方仓库安装
        warn "  尝试 MySQL 官方仓库..."
        wget -q https://dev.mysql.com/get/mysql-apt-config_0.8.33-1_all.deb -O /tmp/mysql-apt.deb
        DEBIAN_FRONTEND=noninteractive dpkg -i /tmp/mysql-apt.deb 2>/dev/null || true
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq mysql-server 2>/dev/null || {
            err "MySQL 安装失败，请手动安装后重试"
        }
    fi

    # 启动 MySQL
    systemctl enable mysql 2>/dev/null || true
    systemctl start mysql 2>/dev/null || service mysql start 2>/dev/null || true
    sleep 3
fi

# 确保 MySQL 在运行
if ! pgrep mysqld &>/dev/null; then
    warn "  MySQL 未启动，尝试启动..."
    systemctl start mysql 2>/dev/null || service mysql start 2>/dev/null || mysqld_safe --skip-grant-tables &>/dev/null &
    sleep 3
fi

log "[3/7] 配置 MySQL 数据库和用户..."

# 写入 SQL 到临时文件，避免 heredoc 在 || 链中的语法问题
cat > /tmp/init_mysql.sql <<'SQLEOF'
ALTER USER 'root'@'localhost' IDENTIFIED BY 'MYSQL_ROOT_PASS';
FLUSH PRIVILEGES;
CREATE DATABASE IF NOT EXISTS `MYSQL_DB` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'MYSQL_USER'@'localhost' IDENTIFIED BY 'MYSQL_PASS';
CREATE USER IF NOT EXISTS 'MYSQL_USER'@'127.0.0.1' IDENTIFIED BY 'MYSQL_PASS';
CREATE USER IF NOT EXISTS 'MYSQL_USER'@'%' IDENTIFIED BY 'MYSQL_PASS';
GRANT ALL PRIVILEGES ON `MYSQL_DB`.* TO 'MYSQL_USER'@'localhost';
GRANT ALL PRIVILEGES ON `MYSQL_DB`.* TO 'MYSQL_USER'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `MYSQL_DB`.* TO 'MYSQL_USER'@'%';
FLUSH PRIVILEGES;
SQLEOF

# 替换占位符
sed -i "s/MYSQL_ROOT_PASS/$MYSQL_ROOT_PASS/g; s/MYSQL_DB/$MYSQL_DB/g; s/MYSQL_USER/$MYSQL_USER/g; s/MYSQL_PASS/$MYSQL_PASS/g" /tmp/init_mysql.sql

# 逐个尝试不同的 MySQL 连接方式
if mysql -u root < /tmp/init_mysql.sql 2>/dev/null; then
    log "  MySQL 配置成功 (root 无密码)"
elif mysql -u root -p"$MYSQL_ROOT_PASS" < /tmp/init_mysql.sql 2>/dev/null; then
    log "  MySQL 配置成功 (root 有密码)"
elif mysql < /tmp/init_mysql.sql 2>/dev/null; then
    log "  MySQL 配置成功 (sudo)"
else
    err "无法连接 MySQL，请手动执行: mysql -u root -p < /tmp/init_mysql.sql"
fi
rm -f /tmp/init_mysql.sql

# ========== 4. Redis ==========
log "[4/7] 安装 Redis..."
if command -v redis-server &>/dev/null; then
    log "  Redis 已安装，跳过"
else
    apt-get install -y -qq redis-server 2>/dev/null || {
        # 从源码编译
        warn "  通过 apt 安装失败，从源码编译..."
        apt-get install -y -qq build-essential tcl 2>/dev/null
        wget -q https://download.redis.io/redis-stable.tar.gz -O /tmp/redis.tar.gz
        tar -xzf /tmp/redis.tar.gz -C /tmp/
        cd /tmp/redis-stable && make -j$(nproc) && make install && cd /
    }
fi

# 配置 Redis
cat > /etc/redis/redis.conf <<'REDIS_CONF'
bind 127.0.0.1
port 6379
daemonize no
supervised systemd
loglevel notice
logfile /var/log/redis/redis.log
save 900 1
save 300 10
save 60 10000
maxmemory 256mb
maxmemory-policy allkeys-lru
REDIS_CONF

systemctl enable redis-server 2>/dev/null || systemctl enable redis 2>/dev/null || true
systemctl restart redis-server 2>/dev/null || systemctl restart redis 2>/dev/null || service redis-server restart 2>/dev/null || true
sleep 2
redis-cli ping 2>/dev/null && log "  Redis 运行正常" || warn "  Redis 可能未启动"

# ========== 5. 部署应用 ==========
log "[5/7] 部署 platform 应用..."

# 创建应用目录
mkdir -p "$APP_DIR"/{bin,logs,sql}

# 如果脚本同目录有 jar，复制过去
if [ -f "$(dirname "$0")/platform-bootstrap.jar" ]; then
    cp "$(dirname "$0")/platform-bootstrap.jar" "$APP_DIR/$APP_JAR"
    log "  从脚本同目录复制 JAR"
elif [ -n "$JAR_DOWNLOAD_URL" ]; then
    wget -q "$JAR_DOWNLOAD_URL" -O "$APP_DIR/$APP_JAR"
    log "  从 URL 下载 JAR"
else
    warn "  未找到 JAR 文件，跳过部署"
    warn "  请手动将 platform-bootstrap.jar 放到 $APP_DIR/"
fi

# 创建 application-prod.yml
cat > "$APP_DIR/application-prod.yml" <<YML
server:
  port: $APP_PORT
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/$MYSQL_DB?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: $MYSQL_USER
    password: $MYSQL_PASS
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  data:
    redis:
      host: 127.0.0.1
      port: $REDIS_PORT
      database: 0
      timeout: 3s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
platform:
  geo:
    cache:
      redis-enabled: true
logging:
  file:
    path: $APP_DIR/logs
  level:
    com.caopan.platform: INFO
YML

# ========== 6. 导入数据 ==========
log "[6/7] 导入地理数据..."
SQL_FILE="$(dirname "$0")/geo_vn_full.sql"
if [ -f "$SQL_FILE" ]; then
    mysql -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" < "$SQL_FILE" 2>/dev/null && {
        log "  数据导入成功"
    } || {
        warn "  数据导入失败，请手动执行: mysql -u $MYSQL_USER -p $MYSQL_DB < $SQL_FILE"
    }
else
    warn "  未找到 geo_vn_full.sql，跳过数据导入"
    warn "  手动导入请执行: mysql -u $MYSQL_USER -p $MYSQL_DB < geo_vn_full.sql"
fi

# ========== 7. 创建 systemd 服务 ==========
log "[7/7] 创建 systemd 服务..."
cat > /etc/systemd/system/platform.service <<UNIT
[Unit]
Description=platform 底座服务
After=network.target mysql.service redis-server.service
Wants=mysql.service redis-server.service

[Service]
Type=simple
User=root
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/java -jar $APP_DIR/$APP_JAR --spring.profiles.active=prod --spring.config.additional-location=file:$APP_DIR/application-prod.yml
Restart=always
RestartSec=10
SuccessExitStatus=143
StandardOutput=append:$APP_DIR/logs/console.log
StandardError=append:$APP_DIR/logs/error.log

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable platform
systemctl restart platform

# 等待启动
sleep 5
if systemctl is-active --quiet platform; then
    log "  ✅ platform 服务运行中 (端口 $APP_PORT)"
else
    warn "  ⚠️  platform 服务状态异常，查看日志: journalctl -u platform -n 50"
fi

# ========== 完成 ==========
echo ""
log "=========================================="
log " 🎉 platform 部署完成"
log "=========================================="
echo ""
echo "  服务地址:    http://$(curl -s ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}'):$APP_PORT"
echo "  API 测试:    curl http://127.0.0.1:$APP_PORT/api/geo/v1/countries?lang=zh"
echo "  查看日志:    journalctl -u platform -f"
echo "  重启服务:    systemctl restart platform"
echo ""
echo "  MySQL:       mysql -u $MYSQL_USER -p'$MYSQL_PASS' $MYSQL_DB"
echo "  Redis:       redis-cli ping"
echo ""
echo "  数据文件:    $APP_DIR/"
echo "  日志目录:    $APP_DIR/logs/"
echo ""
log "=========================================="

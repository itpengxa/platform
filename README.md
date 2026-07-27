# platform（底座）

模块化单体 **基础能力服务**，当前首发能力为 **全球行政区划查询（GEO-001）**。  
业务方通过 HTTP 调用。GEO 调试页与嵌入选择器由 **platform-bootstrap** 静态资源提供（见下文「页面入口」）；`platform-web-ui` 仅为 Maven 占位模块。

| 项 | 说明 |
|----|------|
| 需求 | GEO-001 全球行政区划基础服务 |
| 运行时 | **JDK 21** + Spring Boot **3.3** + Virtual Threads |
| 契约兼容 | `platform-api` / `platform-geo-client` 可按 **JDK 8** 编译接入 |
| 默认端口 | **8088** |
| 数据规模 | 约 250 国家 + ~34 万启用区划（L1~L5，视灌库版本） |

详细设计与交付文档见：`~/Documents/AICoding/GEO-001/`。

---

## 1. 能力一览

- 国家列表（关键词 / ISO2；列表默认**不含**大字段 `iconBase64`）
- 父子级联下钻（`children`）
- 子树查询（`tree`，国家级 depth 封顶可配，行数硬限制可配）
- 祖先链回显（`path`）
- 关键词前缀搜索（**强制国家维度**，禁止 `%`/`_`）
- 多语言展示名：`lang=local|en|zh`（缓存 Key 不含 lang，Service 层计算 `displayName`）
- 三级缓存：Caffeine → Redis → DB（singleflight + TTL 抖动 + 负缓存）
- IP 限流 + DB Token 鉴权（yml 独立开关；online 强制开鉴权）

---

## 2. 技术栈

| 类别 | 选型 |
|------|------|
| 语言 / 框架 | Java 21、Spring Boot 3.3、Spring Web、Virtual Threads |
| 持久化 | MySQL 8、MyBatis-Plus |
| 缓存 | Caffeine（L1）、Redis（L2） |
| 构建 | Maven 多模块 |
| 测试 | JUnit 5 + **Mockito 5**（`@ExtendWith(MockitoExtension)`） |
| i18n | `MessageSource`（`messages*.properties`） |

---

## 3. 模块说明

```
platform/                          # 父 POM
├── platform-api/                  # 契约：VO + GeoService 接口（JDK 8）
├── platform-common/               # Result / ErrorCode / BizException / LangUtil / i18n
├── platform-geo-service/          # 实现 + Entity + Mapper + 三级缓存（无 Controller）
├── platform-geo-web/              # GeoController + TokenIssueController
├── platform-geo-client/           # 可选 HTTP SDK（JDK 8）
├── platform-web-ui/               # Maven 占位（页面在 bootstrap/static）
├── platform-bootstrap/            # 启动入口、Filter、配置、可执行 JAR
├── sql/                           # schema 与校验脚本
└── scripts/geo/                   # 离线灌库 / ETL（应用内无导入接口）
```

**依赖方向（单向）：**

```
platform-bootstrap
  └── platform-geo-web
        ├── platform-geo-service
        │     ├── platform-api
        │     └── platform-common → platform-api
        └── platform-common

platform-geo-client → platform-api
```

| Artifact | packaging | 用途 |
|----------|-----------|------|
| `platform-bootstrap` | 可执行 Fat JAR | 独立部署行政区划服务 |
| `platform-geo-service` | jar | 同 JVM 嵌入（不引入 Web） |
| `platform-geo-client` | jar | 其他工程 HTTP 接入 |
| `platform-api` | jar | 契约依赖（VO / 接口类型） |

> 嵌入 `geo-service` 需要 **JDK 21** 运行时；纯 JDK 8 进程请走 **HTTP + geo-client**。

---

## 4. 架构要点

### 4.1 调用链

```
Client → GeoIpRateLimitFilter → GeoAccessAspect(DB Token) → GeoController
       → GeoServiceImpl → GeoDataCache → TieredCache (L1/L2) → Mapper (L3/DB)
```

- **限流在鉴权之前**（错误 Token 也会被限流，避免 401 洪水）
- 限流 Filter：`HIGHEST+10`；鉴权在 Controller 切面；鉴权/限流均有 **yml 独立开关**

### 4.2 三级缓存

| 层 | 组件 | 说明 |
|----|------|------|
| L1 | Caffeine | 进程内，约 10min；容量可配 |
| L2 | Redis | TTL 可配 + **jitter** 防雪崩 |
| L3 | MySQL | 回源；per-key **singleflight** 防击穿 |
| 负缓存 | `__NULL__` 哨兵 | miss 短 TTL，防穿透 |

Key 前缀：`platform:geo:*`（不含 `lang`）。

### 4.3 数据与 ID 号段

| 层级 | 含义 | ID 号段 |
|------|------|---------|
| L1 | 国家 | `1` ~ `250`（与 country.id 对齐） |
| L2 | 省/州 | `200000000` 起 |
| L3 | 城市 | `300000000` 起 |
| L4 | 区县 | `400000000` 起 |
| L5 | 街镇 | `500000000` 起 |

物化路径示例：`/1/200000001/300000010/`。  
`parent_id` + `path` 与号段一致；历史旧号段可用 `scripts/geo/18_remap_level_ids.py` 迁移。

### 4.4 表

- `geo_country`：国家元数据（iso2/iso3、名称、电话区号等）
- `geo_region`：行政区划树（level、path、status、多语言名等）

DDL 见 `sql/schema.sql`；交付灌库 SQL 见文档目录 `交付/配置/`。

---

## 5. 环境与配置

| Profile | 用途 | 鉴权 | 限流 fail-closed | 说明 |
|---------|------|------|------------------|------|
| `test`（默认） | 本地联调 | 默认关 | 否 | 可打 DEBUG SQL |
| `online` | 预发/生产 | **开**（DB Token） | **是** | Mapper 关闭 DEBUG |
| `prod` | 兼容旧名 | 同 online | 同 online | `spring.profiles.group.prod: online` |
| `dev` | 薄配置/兼容 | — | — | 建议迁移到 `test` |

启动示例：

```bash
# 本地 test
mvn -pl platform-bootstrap -am spring-boot:run

# 生产/预发（先执行 sql/platform_access.sql 或全量 schema）
export MYSQL_URL='jdbc:mysql://...'
export MYSQL_USER=...
export MYSQL_PASSWORD=...
export REDIS_HOST=...
java -jar platform-bootstrap/target/platform-bootstrap-*.jar --spring.profiles.active=online
# 签发 Token（无需鉴权）：
# curl -s -X POST http://host:8088/api/platform/v1/auth/token/issue \
#   -H 'Content-Type: application/json' -d '{"clientCode":"crm"}'
```

常用配置键（`platform.geo.*`）：

| 键 | 含义 | 建议 |
|----|------|------|
| `cache.redis-enabled` | 是否启用 L2 | online=true |
| `cache.jitter-seconds` | TTL 抖动上限 | online 可加大（如 600） |
| `cache.negative-ttl-seconds` | 负缓存 TTL | 默认 30 |
| `cache.tree-max-rows` | 树查询最大行数 | 默认 3000；联调可调大 |
| `cache.tree-country-max-depth` | 国家级根最大 depth | 默认 4（1~5）；test 可设 5 |
| `rate-limit.enabled` | **限流独立开关** | true / false |
| `rate-limit.default-interval-ms` | 普通接口间隔 | 1000 |
| `rate-limit.tree-interval-ms` | tree 间隔 | 2000 |
| `rate-limit.trust-forwarded-headers` | 是否信 XFF | 仅可信网关后开 |
| `rate-limit.fail-closed` | Redis 宕是否拒绝 | online=true |
| `auth.enabled` | **鉴权独立开关** | online 必 true |
| `auth.redis-token-sync-enabled` | Issue 锁 + Redis `valid:{hash}` | 默认 true，需 Redis |
| `auth.issue-lock-seconds` | Issue 分布式锁 TTL | 默认 30 |
| `access-log.stat-enabled` | 调用统计落库 | 默认 true |
---

## 6. 快速开始

```bash
# 1) 建库（或使用交付 ddl）
mysql -u platform -p platform < sql/schema.sql

# 2) 灌数据（离线脚本或交付 DML；应用内无导入接口）
#    参见 scripts/geo/README.md
#    灌库后清缓存：
#    redis-cli --scan --pattern 'platform:geo:*' | xargs redis-cli DEL

# 3) 确认 application-test.yml / 环境变量中的 MySQL、Redis

# 4) 启动
mvn -pl platform-bootstrap -am spring-boot:run
# 默认 profile=test，端口 8088
```

**页面入口（随 bootstrap 启动）：**

| 页面 | URL |
|------|-----|
| API 调试器 | http://localhost:8088/api/validator |
| 地区选择器 | http://localhost:8088/api/picker |
| `/api/` | 同调试器 |

冒烟示例（test 默认无鉴权）：

```bash
curl -s 'http://127.0.0.1:8088/api/geo/v1/countries?lang=zh&keyword=VN' | head
curl -s 'http://127.0.0.1:8088/api/geo/v1/regions/children?parentId=240&lang=zh' | head
curl -s 'http://127.0.0.1:8088/api/geo/v1/regions/tree?countryCode=VN&depth=3&lang=zh' | head
```

online 需先签发再带头：

```bash
TOKEN=$(curl -s -X POST 'http://127.0.0.1:8088/api/platform/v1/auth/token/issue' \
  -H 'Content-Type: application/json' -d '{"clientCode":"crm","clientName":"CRM"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
curl -s -H "X-Platform-Token: $TOKEN" \
  'http://127.0.0.1:8088/api/geo/v1/countries?lang=zh'
# 或 Authorization: Bearer <token>
```

---

## 7. HTTP API

统一响应：`{ "code": 0, "message": "success", "data": ... }`  
业务错误码见 `ErrorCode`（如 40000 参数、40001 父不存在、40029 限流、40100 未授权）。

| 方法 | 路径 | 主要参数 | 说明 |
|------|------|----------|------|
| POST | `/api/platform/v1/auth/token/issue` | `clientCode`*, `clientName` | **无需鉴权**签发长效 Token；再调即换新并吊销旧值 |
| GET | `/api/geo/v1/countries` | `lang`, `keyword` | 国家列表；keyword 禁 `%`/`_` |
| GET | `/api/geo/v1/regions/children` | `parentId`*, `lang` | 直属子级；父不存在 → 40001 |
| GET | `/api/geo/v1/regions/tree` | `countryCode`*, `rootId`, `depth`, `lang` | depth 默认 3，范围 1~5；国家级封顶见 `tree-country-max-depth`；超 `tree-max-rows` 拒绝 |
| GET | `/api/geo/v1/regions/{id}/path` | `lang` | 国家→…→当前祖先链 |
| GET | `/api/geo/v1/regions/search` | `keyword`*, `countryCode`*, `level`, `limit`, `lang` | 前缀匹配；keyword≥2；limit 1~100，默认 20 |

\* 必填。`lang`：`local`（默认）/ `en` / `zh`。

**限流：** `platform.geo.rate-limit.enabled` 独立开关；同 IP 默认约 1 qps，tree 约 0.5 qps；超限 HTTP **429**。  
**鉴权：** `platform.geo.auth.enabled` 独立开关；开启后 geo 接口需 DB Token（`X-Platform-Token` / Bearer）；失败 **401**。  
**统计：** 切面异步写入 `platform_api_access_stat`（应用/时间/接口/参数/成败）。
---

## 8. 单元测试

各业务模块已补充 Mockito 单测（**不使用 PowerMock**：JDK 21 不兼容）。

```bash
mvn test
```

| 模块 | 覆盖重点 |
|------|----------|
| `platform-common` | `LangUtil`、`Result`、`ErrorMessages` |
| `platform-geo-service` | `PathUtil`、`GeoCacheKeys`、`TieredCache`、`GeoDataCache`、`GeoServiceImpl`、`AccessTokenService` |
| `platform-geo-web` | `GeoController`、`TokenIssueController`（纯 Mockito，不启容器） |
| `platform-bootstrap` | IP 限流 Filter、GeoAccessAspect、启动门禁 |

风格：`@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks`。

---

## 9. 数据灌入与脚本

应用**不提供**在线导入接口。离线 ETL 见：

- 仓库内：[`scripts/geo/README.md`](scripts/geo/README.md)
- 交付 SQL：`Documents/AICoding/GEO-001/交付/配置/`（ddl + country/region DML）

数据来源署名：countries-states-cities-database（ODbL）；L4/L5 等扩展可含 GeoNames 等来源，以脚本注释为准。

---

## 10. 相关文档

| 文档 | 位置 |
|------|------|
| 需求分析 | `Documents/AICoding/GEO-001/需求分析/` |
| 概要设计 | `Documents/AICoding/GEO-001/设计/统一概要设计-platform-geo.md` |
| 后端用例 / 功能测试 / 性能安全复审 | `Documents/AICoding/GEO-001/测试/`、`交付/测试/` |
| 分支记录 | `Documents/AICoding/GEO-001/分支记录.md` |

---

## 11. 构建与产物

```bash
mvn -pl platform-bootstrap -am clean package -DskipTests
# 产物：platform-bootstrap/target/platform-bootstrap-1.0.0-SNAPSHOT.jar
```

版本：`1.0.0-SNAPSHOT`（父 POM `com.caopan:platform`）。

# platform（底座）

模块化单体基础能力服务。JDK 21 + Spring Boot 3.3 + Virtual Threads。

## 模块

| 模块 | 说明 |
|------|------|
| platform-api | 契约层 VO/Enum + GeoService（JDK 8） |
| platform-common | 统一响应、异常、LangUtil |
| platform-geo-service | 行政区划实现 + Mapper（无 Controller） |
| platform-geo-web | 行政区划 HTTP Controller |
| platform-geo-client | 可选 HTTP SDK（JDK 8） |
| platform-bootstrap | 启动与打包入口 |

## 快速开始

```bash
# 1. 建库
mysql -uroot -p < sql/schema.sql

# 2. 离线脚本灌入 geo 数据（应用内无导入接口）

# 3. 修改 platform-bootstrap/src/main/resources/application-dev.yml 数据源

# 4. 启动
mvn -pl platform-bootstrap -am spring-boot:run -Dspring-boot.run.profiles=dev
```

## API

- `GET /api/geo/v1/countries`
- `GET /api/geo/v1/regions/children?parentId=`
- `GET /api/geo/v1/regions/tree?countryCode=`
- `GET /api/geo/v1/regions/{id}/path`
- `GET /api/geo/v1/regions/search?keyword=`

数据来源署名：countries-states-cities-database (ODbL)。

# platform-web-ui

GEO 相关 H5 已统一放在 **platform-bootstrap** 静态资源中，随 `platform-bootstrap`（默认 8088）一起提供：

| 用途 | 地址 |
|------|------|
| API 调试器 | http://localhost:8088/api/validator |
| 地区选择器 | http://localhost:8088/api/picker |
| 根路径 | http://localhost:8088/api/ → 调试器 |

源码路径：

- `platform-bootstrap/src/main/resources/static/geo-validator.html`
- `platform-bootstrap/src/main/resources/static/api/geo-picker.html`
- `platform-bootstrap/src/main/resources/static/api/geo-picker.js`

本模块保留为 Maven 占位，无静态文件。

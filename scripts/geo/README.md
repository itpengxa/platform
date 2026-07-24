# 全球行政区划 ETL（scripts/geo）

离线灌库与治理脚本目录。应用运行期**无提供**导入 HTTP 接口；数据变更一律走本目录脚本或交付 DML。

默认库连接：`platform/platform@127.0.0.1/platform`（可用环境变量覆盖，见各脚本 `--help` / 文件头注释）。

---

## 脚本一览

| 脚本 | 作用 |
|------|------|
| `01_download_vn.sh` ~ `08_reshape_vn_hierarchy.py` | 早期越南专用链路（下载/清洗/校验/导入/整形），全球方案已以 10+ 为主 |
| `10_download_world_l123.sh` | 下载 CSC 世界 1~3 级源数据 |
| `11_load_world_l123.py` | 加载 L1~L3 入库（号段编码 + path） |
| `12_download_geonames_l45.sh` | 下载 GeoNames L4/L5（优先 allCountries，失败按国包） |
| `12b_download_l4_by_city.py` | 国包仍缺时：按已有 L3 调 GeoNames API 拉下级（需 `GEONAMES_USER`） |
| `13_import_l4_match_l3.py` | 父名匹配 L3，批量导入 L4 |
| `14_backfill_l3_from_adm2.py` | L3 回填/补齐 |
| `14_reshape_world_hierarchy.py` | 世界层级整形 |
| `15_import_l5_match_l4.py` | 父名匹配 L4，导入 L5 |
| `16_audit_duplicates.py` | 重复数据审计 |
| `17_dedupe_same_parent_name.py` | 同父同名去重（软删） |
| `18_remap_level_ids.py` | 旧号段 → 现行层级号段重映射（含 path/parent 重建） |

---

## ID 号段约定

执行加载/重映射后，应满足：

| 层级 | 含义 | ID |
|------|------|-----|
| L1 | 国家 | 保持 `country.id`（约 1~250） |
| L2 | 省/州 | `200_000_000` 起自增 |
| L3 | 城市 | `300_000_000` 起自增 |
| L4 | 区县 | `400_000_000` 起自增 |
| L5 | 街镇 | `500_000_000` 起自增 |

`parent_id`、物化 `path`（如 `/1/200000001/300000010/`）与号段同步。

若库内仍是旧号段：

```bash
python3 18_remap_level_ids.py          # dry-run
python3 18_remap_level_ids.py --apply
```

---

## 推荐流程（全球 L1~L5）

### 1~3 级（CSC）

```bash
bash 10_download_world_l123.sh
python3 11_load_world_l123.py --apply
```

### 4 级（GeoNames / 回退）

```bash
# 1) 优先 allCountries；失败则按国 XX.zip
bash 12_download_geonames_l45.sh
# 或指定国家：
bash 12_download_geonames_l45.sh TH US JP

# 2) 国包仍缺时：按已有 L3 城市调 GeoNames API（需账号）
export GEONAMES_USER=你的用户名
python3 12b_download_l4_by_city.py --countries TH --limit-cities 5   # 先小范围试
python3 12b_download_l4_by_city.py --countries TH --apply

# 3) 已有国别/全量 txt：父名匹配 L3 批量入库
python3 13_import_l4_match_l3.py --countries TH --apply
```

约定：一般不导入 CN；VN 若已有 4 级可跳过（以脚本内逻辑为准）。

### 5 级

父名匹配已有 **L4**，字段规则与 L4 导入同构：

```bash
python3 15_import_l5_match_l4.py --countries TH --apply   # 参数以脚本为准
```

### 治理

```bash
python3 16_audit_duplicates.py
python3 17_dedupe_same_parent_name.py --apply   # 确认 dry-run 后再 apply
```

---

## 灌库后必做

应用使用三级缓存，灌库或大批量变更后必须清 Redis，否则最长可脏读至 L2 TTL（可达 12~24h）：

```bash
redis-cli --scan --pattern 'platform:geo:*' | xargs redis-cli DEL
# macOS 若无 xargs -r，可改为：
# redis-cli --scan --pattern 'platform:geo:*' | xargs -n 100 redis-cli DEL
```

交付侧现成 DML 也可直接导入（无需跑全套 ETL），见：

`Documents/AICoding/GEO-001/交付/配置/README.md`

---

## 库账号与环境变量

| 项 | 默认 |
|----|------|
| Host | `127.0.0.1` |
| Database / User / Password | `platform` / `platform` / `platform` |

具体覆盖方式见各 Python 脚本（常见为 `MYSQL_*` 或命令行参数）。GeoNames API 需 `GEONAMES_USER`。

---

## 注意

- 生产数据变更先 **dry-run**，再 `--apply`；重要操作前备份表（如 `18_remap` 可能生成 bak 表）。
- 去重为软删（`status`），以脚本实现为准。
- 源数据许可：CSC 等为 ODbL；GeoNames 遵循其服务条款与配额。

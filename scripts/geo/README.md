# 全球行政区划 ETL

## 1~3 级（CSC）

```bash
bash 10_download_world_l123.sh
python3 11_load_world_l123.py --apply
```

说明：按层级号段编码（一眼能看出级别），`parent_id` / `path` 同步使用新 id：
- L1 = country.id（1~250，保持）
- L2 = 200_000_000 起自增
- L3 = 300_000_000 起自增
- L4 = 400_000_000 起自增
- L5 = 500_000_000 起自增

若库内仍是旧号段，可执行：
```bash
python3 18_remap_level_ids.py          # dry-run
python3 18_remap_level_ids.py --apply
```

## 4 级数据下载（三级回退）

```bash
# 1) 优先 allCountries；失败则按国 XX.zip
bash 12_download_geonames_l45.sh
# 或指定国家：
bash 12_download_geonames_l45.sh TH US JP

# 2) 国包仍缺时：按已有 L3 城市调 GeoNames API 拉下级（需账号）
export GEONAMES_USER=你的用户名
python3 12b_download_l4_by_city.py --countries TH --limit-cities 5   # 先小范围试
python3 12b_download_l4_by_city.py --countries TH --apply

# 3) 若已有国别/全量 txt，用父名匹配 L3 批量入库
python3 13_import_l4_match_l3.py --countries TH --apply
```

不导入 CN；VN 已有 4 级默认跳过。

## 5 级

同一套逻辑：父名匹配已有 **L4**，其余字段同上（脚本可按 13 同构扩展）。

## 库账号

默认 `platform/platform@127.0.0.1/platform`，可用环境变量覆盖。

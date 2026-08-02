#!/usr/bin/env python3
"""
越南政府行政区划数据爬取脚本

数据来源：
  1️⃣ GSO (Tổng cục Thống kê) — 官方 API，最权威
  2️⃣ Wikipedia — 结构化表格，次选
  3️⃣ OpenStreetMap Overpass API — 全球最完整，备选

输出：
  - geo_region 兼容的 JSONL（可直接导入）
  - 验证报告（覆盖率）

用法：
  python3 scrape_vn_gov.py
  python3 scrape_vn_gov.py --source gso
  python3 scrape_vn_gov.py --source wikipedia
  python3 scrape_vn_gov.py --source osm

依赖:
  pip install requests beautifulsoup4 lxml
"""

import argparse
import csv
import io
import json
import os
import re
import sys
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    print("❌ 需要 requests: pip install requests")
    sys.exit(1)

# ===== 配置 =====
OUTPUT_DIR = Path(__file__).resolve().parent / "scraped"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
COUNTRY_CODE = "VN"
COUNTRY_ID = 240
USER_AGENT = "Mozilla/5.0 (compatible; platform-geo-validate/1.0)"

# ===== GSO 官方 API =====
# GSO 行政区域数据库 API 端点
GSO_API = "https://danhmuchanhchinh.gso.gov.vn/DMC/GetDSDonViHanhChinh"
GSO_PARAMS = {
    "cap": 1,       # 1=tỉnh(省), 2=huyện(县), 3=xã(社)
    "trangthai": 1, # 1=đang hoạt động(运行中)
}


def fetch_gso(cap, retries=3):
    """调用 GSO API 获取指定层级的数据"""
    params = {**GSO_PARAMS, "cap": cap}
    for attempt in range(retries):
        try:
            r = requests.get(GSO_API, params=params, timeout=15, headers={"User-Agent": USER_AGENT})
            r.raise_for_status()
            data = r.json()
            if isinstance(data, list):
                return data
            # 有时返回 {data: [...]}
            if isinstance(data, dict) and "data" in data:
                return data["data"]
            return []
        except Exception as e:
            if attempt < retries - 1:
                time.sleep(2)
                continue
            print(f"  ⚠️ GSO cap={cap} 请求失败: {e}")
            return None


def scrape_gso():
    """从 GSO 爬取越南全量 3 级行政区划"""
    print("\n📡 GSO 官方 API...")
    all_provinces = fetch_gso(1)
    if not all_provinces:
        return None

    print(f"  省/直辖市: {len(all_provinces)}")
    regions = []
    errors = 0

    for prov in all_provinces:
        prov_id = prov.get("ma") or prov.get("id", "")
        prov_name = prov.get("ten") or prov.get("name", "")
        prov_code = prov.get("matinh", "") or prov.get("province_code", "")
        regions.append({
            "source_id": f"GSO_{prov_id}",
            "name": prov_name,
            "name_en": "",
            "name_ch": "",
            "level": 2,
            "region_type": "PROVINCE",
            "parent_source_id": None,
            "code": prov_code,
        })

        # 查询下级县
        districts = fetch_gso(2)
        if not districts:
            # 也可以按省查询 ma=prov_id
            errors += 1
            continue

        for dist in districts:
            if str(dist.get("matinh", "")) != str(prov_id) and dist.get("parent_id") != prov_id:
                continue
            dist_id = dist.get("ma") or dist.get("id", "")
            dist_name = dist.get("ten") or dist.get("name", "")
            dist_code = dist.get("mahuyen", "") or dist.get("district_code", "")
            regions.append({
                "source_id": f"GSO_{dist_id}",
                "name": dist_name,
                "name_en": "",
                "name_ch": "",
                "level": 3,
                "region_type": "DISTRICT",
                "parent_source_id": f"GSO_{prov_id}",
                "code": dist_code,
            })

            # 查询下级社
            communes = fetch_gso(3)
            if not communes:
                continue
            for com in communes:
                if str(com.get("mahuyen", "")) != str(dist_id) and com.get("parent_id") != dist_id:
                    continue
                com_id = com.get("ma") or com.get("id", "")
                com_name = com.get("ten") or com.get("name", "")
                com_type = com.get("loai", "") or com.get("type", "")  # Xã/Phường/Thị trấn
                com_code = com.get("maxa", "") or com.get("commune_code", "")
                regions.append({
                    "source_id": f"GSO_{com_id}",
                    "name": com_name,
                    "name_en": "",
                    "name_ch": "",
                    "level": 4,
                    "region_type": _vn_commune_type(com_type),
                    "parent_source_id": f"GSO_{dist_id}",
                    "code": com_code,
                })

    by_level = {}
    for r in regions:
        by_level.setdefault(r["level"], 0)
        by_level[r["level"]] += 1

    print(f"  结果: Level 2={by_level.get(2,0)} Level 3={by_level.get(3,0)} Level 4={by_level.get(4,0)}")
    return regions


def _vn_commune_type(t):
    """映射越南社级类型到 region_type"""
    t = t.lower()
    if "phường" in t or "phuong" in t:
        return "WARD"
    if "thị trấn" in t or "thi tran" in t or "town" in t:
        return "TOWN"
    return "COMMUNE"


# ===== Wikipedia =====
WIKI_URL = "https://en.wikipedia.org/w/api.php"


def fetch_wiki_table(title):
    """从 Wikipedia 解析指定页面的表格"""
    params = {
        "action": "parse",
        "page": title,
        "format": "json",
        "prop": "text",
        "section": 0,
    }
    try:
        r = requests.get(WIKI_URL, params=params, timeout=15, headers={"User-Agent": USER_AGENT})
        r.raise_for_status()
        data = r.json()
        html = data.get("parse", {}).get("text", {}).get("*", "")
        return html
    except Exception as e:
        print(f"  ⚠️ Wikipedia 请求失败: {e}")
        return None


def scrape_wikipedia():
    """从 Wikipedia 爬取越南行政区划"""
    print("\n📚 Wikipedia...")
    html = fetch_wiki_table("Provinces_of_Vietnam")
    if not html:
        html = fetch_wiki_table("List_of_administrative_divisions_of_Vietnam")
    if not html:
        return None

    # 简单提取：找表格行
    # Wikipedia 表格格式：<table class="wikitable"> <tr><td>数据</td></tr>
    import re as _re
    tables = _re.findall(r'<table[^>]*class="[^"]*wikitable[^"]*"[^>]*>(.*?)</table>', html, _re.DOTALL)
    print(f"  发现 {len(tables)} 个 wikitable")

    regions = []
    for tbl in tables:
        rows = _re.findall(r'<tr>(.*?)</tr>', tbl, _re.DOTALL)
        for row in rows:
            cells = _re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, _re.DOTALL)
            if len(cells) < 3:
                continue
            # 清理 HTML 标签
            name = _re.sub(r'<[^>]+>', '', cells[0]).strip()
            name_en = _re.sub(r'<[^>]+>', '', cells[1]).strip() if len(cells) > 1 else ""
            regions.append({
                "source": "WIKI",
                "name": name,
                "name_en": name_en,
                "level": 2,
                "region_type": "PROVINCE",
            })

    print(f"  提取到 {len(regions)} 个省")
    return regions


# ===== OpenStreetMap Overpass API =====
OSM_API = "https://overpass-api.de/api/interpreter"


def scrape_osm():
    """从 OpenStreetMap Overpass API 爬取"""
    print("\n🗺️ OpenStreetMap Overpass API...")
    # 查询 admin_level=2 到 admin_level=5 的所有 relation
    query = """
    [out:json][timeout:60];
    area["ISO3166-1"="VN"]->.a;
    (
      rel(area.a)[admin_level="2"];
      rel(area.a)[admin_level="3"];
      rel(area.a)[admin_level="4"];
    );
    out body;
    >;
    out skel qt;
    """
    try:
        r = requests.post(OSM_API, data=query, timeout=90, headers={"User-Agent": USER_AGENT})
        r.raise_for_status()
        data = r.json()
    except Exception as e:
        print(f"  ⚠️ Overpass 请求失败: {e}")
        return None

    elements = data.get("elements", [])
    print(f"  返回 {len(elements)} 个元素")

    regions = []
    for el in elements:
        if el.get("type") != "relation":
            continue
        tags = el.get("tags", {})
        admin_level = tags.get("admin_level", "")
        name = tags.get("name:vi", "") or tags.get("name", "")
        name_en = tags.get("name:en", "") or tags.get("name", "")
        # 映射 admin_level 到我们的 level
        level_map = {"2": 2, "3": 3, "4": 4}
        level = level_map.get(admin_level, 0)
        if level < 2:
            continue

        type_map = {"2": "PROVINCE", "3": "DISTRICT", "4": "COMMUNE"}
        regions.append({
            "source": "OSM",
            "source_id": str(el["id"]),
            "name": name,
            "name_en": name_en,
            "level": level,
            "region_type": type_map.get(admin_level, "UNKNOWN"),
            "osm_id": el["id"],
        })

    by_level = {}
    for r in regions:
        by_level.setdefault(r["level"], 0)
        by_level[r["level"]] += 1

    print(f"  结果: Level 2={by_level.get(2,0)} Level 3={by_level.get(3,0)} Level 4={by_level.get(4,0)}")
    return regions


# ===== 输出 =====
def save_results(regions, source_name):
    """保存结果到 JSONL 文件"""
    if not regions:
        return

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = OUTPUT_DIR / f"vn_gov_{source_name}_{timestamp}.jsonl"

    with open(path, "w", encoding="utf-8") as f:
        for r in regions:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"\n  已保存: {path} ({len(regions)} 条)")
    return path


def print_summary(regions, source_name):
    """打印汇总"""
    if not regions:
        return
    by_level = {}
    for r in regions:
        by_level.setdefault(r["level"], 0)
        by_level[r["level"]] += 1

    print(f"\n{'='*50}")
    print(f"  {source_name.upper()} 汇总")
    print(f"{'='*50}")
    for lv in sorted(by_level):
        print(f"  Level {lv}: {by_level[lv]:>6} 条")
    print(f"  合计:    {sum(by_level.values()):>6} 条")

    # 与现有数据对比
    try:
        import pymysql
        conn = pymysql.connect(
            host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
            port=int(os.environ.get("MYSQL_PORT", 3306)),
            user=os.environ.get("MYSQL_USER", "root"),
            password=os.environ.get("MYSQL_PASSWORD", ""),
            database=os.environ.get("MYSQL_DB", "platform"),
            charset="utf8mb4",
        )
        cur = conn.cursor()
        for lv in sorted(by_level):
            cur.execute(f"SELECT COUNT(*) FROM geo_region WHERE country_code='VN' AND level={lv}")
            existing = cur.fetchone()[0]
            diff = by_level[lv] - existing
            sign = "+" if diff > 0 else ""
            print(f"  现有 Level {lv}: {existing:>6} 条 | 新增: {sign}{diff}")
        cur.close()
        conn.close()
    except ImportError:
        pass


def main():
    parser = argparse.ArgumentParser(description="越南政府行政区划数据爬取")
    parser.add_argument("--source", choices=["gso", "wikipedia", "osm", "all"], default="all",
                        help="数据来源（默认 all）")
    args = parser.parse_args()

    print(f"{'='*55}")
    print(f"  越南行政区划数据爬取")
    print(f"  {datetime.now().isoformat()}")
    print(f"{'='*55}")

    sources = {
        "gso": ("GSO 官方", scrape_gso),
        "wikipedia": ("Wikipedia", scrape_wikipedia),
        "osm": ("OpenStreetMap", scrape_osm),
    }

    if args.source == "all":
        targets = sources.values()
    else:
        targets = [(args.source, sources[args.source][1])]

    all_results = {}
    for name, func in targets:
        try:
            result = func()
            if result:
                all_results[name] = result
                save_results(result, name.replace(" ", "_").lower())
                print_summary(result, name)
        except Exception as e:
            print(f"  ❌ {name} 出错: {e}")

    print(f"\n{'='*55}")
    if all_results:
        print(f"  完成！共 {len(all_results)} 个来源成功")
        print(f"  文件保存在: {OUTPUT_DIR}/")
    else:
        print(f"  ❌ 所有来源均失败")
        print(f"  可能是因为网络限制，尝试在另一台机器运行")
    print(f"{'='*55}")


if __name__ == "__main__":
    main()

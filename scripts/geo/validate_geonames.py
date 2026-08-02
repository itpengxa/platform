#!/usr/bin/env python3
"""
GeoNames 交叉验证脚本

用法:
  # 抽 100 条验证（默认）
  python3 validate_geonames.py

  # 指定数量和采样方式
  python3 validate_geonames.py --limit 500 --mode random

  # 只验证 Level 4
  python3 validate_geonames.py --level 4

  # 使用自己的 GeoNames 账号（demo 日限 ~2000 次）
  python3 validate_geonames.py --username 你的用户名

原理:
  从 geo_region 取数据 → 用 geonameid（source_id）查 GeoNames API →
  对比官方名称/坐标/层级 → 输出验证报告

依赖:
  pip install pymysql  (本地 MySQL 连接)
"""

import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

GEONAMES_API = "http://api.geonames.org/get"
GEONAMES_SEARCH = "http://api.geonames.org/search"
DEFAULT_USERNAME = "demo"  # 免费 demo 账号，日限 ~2000 次。注册: https://www.geonames.org/login
DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", 3306)),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", ""),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}


def haversine_km(lat1, lon1, lat2, lon2):
    """Haversine 距离（公里）"""
    if None in (lat1, lon1, lat2, lon2):
        return None
    R = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def api_get(url, params, retries=3):
    """调用 GeoNames API，带重试"""
    params["type"] = "json"  # 强制 JSON 格式
    for attempt in range(retries):
        try:
            full_url = url + "?" + urllib.parse.urlencode(params)
            req = urllib.request.Request(full_url, headers={"User-Agent": "platform-geo-validate/1.0"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, OSError) as e:
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            return {"status": "error", "message": str(e)}


def fetch_by_geonameid(geonameid, username):
    """用 geonameid 精确查询 GeoNames"""
    params = {"geonameId": geonameid, "username": username, "style": "MEDIUM"}
    data = api_get(GEONAMES_API, params)
    if "status" in data and data.get("status", {}).get("value"):
        return None, f"API错误: {data['status'].get('value')} - {data['status'].get('message','')}"
    if "geonameId" not in data:
        return None, "未找到该 geonameid"
    return data, None


def search_by_name(name, country, username):
    """按名称搜索 GeoNames"""
    params = {
        "q": name,
        "country": country,
        "maxRows": 1,
        "username": username,
        "style": "MEDIUM",
    }
    data = api_get(GEONAMES_SEARCH, params)
    if "geonames" not in data or not data["geonames"]:
        return None, "搜索无结果"
    return data["geonames"][0], None


def load_local_index(filepath):
    """加载本地 GeoNames dump 文件（如 VN.txt）"""
    idx = {}
    if not filepath or not os.path.exists(filepath):
        return idx
    with open(filepath, encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) >= 8:
                idx[parts[0]] = {
                    "name": parts[1], "ascii": parts[2],
                    "lat": float(parts[4]) if parts[4] else 0,
                    "lng": float(parts[5]) if parts[5] else 0,
                    "fcode": parts[7],
                }
    return idx


def validate_record(record, username, local_idx=None):
    """
    验证单条记录。返回 (status, details)
    status: PASS / WARN / FAIL
    """
    rid, name, name_en, level, region_type, lat, lng, source_id, country_code = record
    lat = float(lat) if lat is not None else None
    lng = float(lng) if lng is not None else None

    # 优先用 geonameid 精确查询
    geo_data = None
    error = None

    # 1. 优先用本地文件验证（更快、不限流）
    if local_idx and source_id and source_id in local_idx:
        li = local_idx[source_id]
        geo_data = {
            "name": li["name"],
            "lat": str(li["lat"]),
            "lng": str(li["lng"]),
            "fcode": li["fcode"],
            "geonameId": source_id,
            "countryCode": country_code,
            "_source": "local",
        }
    elif local_idx and source_id:
        geo_data, error = None, "本地文件未找到该 ID"

    # 2. 本地没找到，走在线 API
    if not geo_data:
        if source_id:
            geo_data, error = fetch_by_geonameid(source_id, username)
        if not geo_data:
            geo_data, error = search_by_name(name, country_code, username)

    if not geo_data:
        return "FAIL", {"reason": error or "未找到", "name": name}

    # 对比维度
    gn_name = geo_data.get("name", "")
    gn_lat = float(geo_data.get("lat", 0))
    gn_lng = float(geo_data.get("lng", 0))
    gn_fcode = geo_data.get("fcode", "")
    gn_geonameid = geo_data.get("geonameId", "")
    gn_country = geo_data.get("countryCode", "")

    # 1. 名称匹配
    name_ok = name.lower() in gn_name.lower() or gn_name.lower() in name.lower()

    # 2. 坐标距离
    dist = haversine_km(lat, lng, gn_lat, gn_lng) if lat and lng else None
    coord_ok = dist is not None and dist < 20  # 20km 以内算通过

    # 3. 判断结果
    issues = []
    if not name_ok:
        issues.append(f"名称不匹配: '{name}' vs '{gn_name}'")
    if not coord_ok and dist is not None:
        issues.append(f"坐标偏差 {dist:.1f}km (阈值20km)")
    if gn_country and gn_country != country_code:
        issues.append(f"国家码不匹配: {country_code} vs {gn_country}")

    if not issues:
        return "PASS", {
            "gn_name": gn_name, "gn_geonameid": gn_geonameid,
            "gn_fcode": gn_fcode, "distance_km": round(dist, 1) if dist else None,
        }
    elif len(issues) <= 1:
        return "WARN", {
            "gn_name": gn_name, "gn_geonameid": gn_geonameid,
            "issues": issues, "distance_km": round(dist, 1) if dist else None,
        }
    else:
        return "FAIL", {
            "gn_name": gn_name, "gn_geonameid": gn_geonameid,
            "issues": issues, "distance_km": round(dist, 1) if dist else None,
        }


def main():
    parser = argparse.ArgumentParser(description="GeoNames 交叉验证工具")
    parser.add_argument("--limit", type=int, default=100, help="验证条数（默认 100）")
    parser.add_argument("--level", type=int, default=None, help="只验证指定层级（4/5）")
    parser.add_argument("--mode", choices=["random", "sequential"], default="random",
                        help="采样方式（默认 random）")
    parser.add_argument("--username", default=DEFAULT_USERNAME, help="GeoNames 用户名")
    parser.add_argument("--country", default="VN", help="国家代码")
    args = parser.parse_args()

    # 1. 从数据库取数据
    try:
        import pymysql
    except ImportError:
        print("❌ 需要 pymysql: pip install pymysql")
        sys.exit(1)

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()

    where = f"country_code='{args.country}'"
    if args.level:
        where += f" AND level={args.level}"

    order = "RAND()" if args.mode == "random" else "id"

    sql = f"""SELECT id, name, name_en, level, region_type,
                     latitude, longitude, source_id, country_code
              FROM geo_region
              WHERE {where}
              ORDER BY {order}
              LIMIT {args.limit}"""
    cur.execute(sql)
    records = cur.fetchall()
    cur.close()
    conn.close()

    total = len(records)
    print(f"\n{'='*60}")
    print(f"  GeoNames 交叉验证")
    print(f"  数据库: {DB_CONFIG['database']} @ {DB_CONFIG['host']}")
    print(f"  国家: {args.country}")
    print(f"  采样: {total} 条 ({args.mode})")
    if args.level:
        print(f"  层级: Level {args.level}")
    print(f"{'='*60}\n")

    # 2.5 加载本地 GeoNames 文件（用于加速和离线验证）
    geonames_file = os.environ.get("GEONAMES_FILE", f"raw/{args.country}.txt")
    if not os.path.exists(geonames_file):
        geonames_file = f"/Users/a0000/IdeaProjects/platform/scripts/geo/raw/{args.country}.txt"
    local_idx = load_local_index(geonames_file)
    if local_idx:
        print(f"  本地 GeoNames 索引: {len(local_idx)} 条")
    else:
        print(f"  未找到本地 GeoNames 文件 {geonames_file}，将使用在线 API")

    # 3. 逐条验证
    results = {"PASS": [], "WARN": [], "FAIL": [], "SKIP": []}
    for i, record in enumerate(records, 1):
        rid, name = record[0], record[1]
        print(f"  [{i}/{total}] id={rid} {name[:30]:30s} ... ", end="", flush=True)
        status, detail = validate_record(record, args.username, local_idx)
        results[status].append({"id": rid, "name": name, **detail})

        status_icon = {"PASS": "✅", "WARN": "⚠️", "FAIL": "❌", "SKIP": "⏭️"}
        print(f"{status_icon.get(status, '?')} {status}")
        if status in ("WARN", "FAIL"):
            for issue in detail.get("issues", []):
                print(f"           {issue}")

        # API 限流
        if args.username == "demo" and i < total:
            time.sleep(1.5)  # demo 账号建议 1.5s
        elif i < total:
            time.sleep(0.3)

    # 3. 汇总报告
    passed = len(results["PASS"])
    warned = len(results["WARN"])
    failed = len(results["FAIL"])
    skipped = len(results["SKIP"])

    print(f"\n{'='*60}")
    print(f"  验证报告")
    print(f"{'='*60}")
    print(f"  总计:      {total} 条")
    print(f"  ✅ 通过:    {passed} 条 ({passed * 100 // max(total, 1)}%)")
    print(f"  ⚠️ 警告:    {warned} 条 ({warned * 100 // max(total, 1)}%)")
    print(f"  ❌ 失败:   {failed} 条 ({failed * 100 // max(total, 1)}%)")
    print(f"  ⏭️ 跳过:    {skipped} 条")
    print()

    if warned > 0:
        print("⚠️ 警告明细（名称或坐标轻微偏差）:")
        for r in results["WARN"][:10]:
            print(f"  id={r['id']} {r['name'][:25]:25s} → {r.get('gn_name','?')}")
            for issue in r.get("issues", []):
                print(f"    {issue}")
        if len(results["WARN"]) > 10:
            print(f"  ... 还有 {len(results['WARN'])-10} 条")

    if failed > 0:
        print("\n❌ 失败明细（完全无法匹配）:")
        for r in results["FAIL"][:10]:
            print(f"  id={r['id']} {r['name'][:25]:25s} → {r.get('reason','?')}")

    print(f"\n{'='*60}")
    if failed == 0 and warned == 0:
        print(f"  🎉 全部通过！数据与 GeoNames 一致")
    elif failed == 0:
        print(f"  ⚠️ {warned} 条警告，建议复查")
    else:
        print(f"  ❌ {failed} 条失败，需要修正")
    print(f"{'='*60}")

    # 4. 保存报告
    report = {
        "timestamp": datetime.now().isoformat(),
        "total": total, "passed": passed,
        "warned": warned, "failed": failed,
        "skipped": skipped,
        "details": results,
    }
    report_path = f"geonames_validate_{args.country}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n报告已保存: {report_path}")


if __name__ == "__main__":
    main()

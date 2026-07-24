#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12b_download_l4_by_city.py
==========================
当国家 dump 拉不到时：按库内已有 L3 城市，调 GeoNames API 拉下级（4 级）并直接入库。

逻辑与 13 一致：
  - 匹配到的父 = 当前 L3
  - parent_id / country_code / level / path 用我们自己的
  - name/name_en/经纬度/source_id 用接口返回
  - id 自增；source_id 防重复
  - 跳过 CN、VN

需要免费账号：https://www.geonames.org/login
  export GEONAMES_USER=你的用户名

用法:
  python3 12b_download_l4_by_city.py --countries TH --limit-cities 20   # dry-run
  python3 12b_download_l4_by_city.py --countries TH --apply
  python3 12b_download_l4_by_city.py --apply --sleep 1.1
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Dict, List, Optional, Set

import pymysql

BATCH = f"L4_CITYAPI_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"
SKIP = {"CN", "VN"}
DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}
API = "http://api.geonames.org"


def hr(title: str = "", char: str = "─", width: int = 64) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print(char * left + f" {title} " + char * (pad - left))
    else:
        print(char * width)


def api_get(path: str, params: dict, user: str) -> dict:
    params = dict(params)
    params["username"] = user
    url = f"{API}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": "platform-geo-etl/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def search_city_geoname_id(user: str, country: str, name: str, lat, lng) -> Optional[int]:
    """用名称(+坐标)在 GeoNames 里定位城市 geonameId。"""
    data = api_get("/searchJSON", {
        "name": name,
        "country": country,
        "maxRows": 5,
        "featureClass": "P",
        "style": "SHORT",
    }, user)
    geonames = data.get("geonames") or []
    if not geonames and name:
        data = api_get("/searchJSON", {
            "q": name,
            "country": country,
            "maxRows": 5,
            "style": "SHORT",
        }, user)
        geonames = data.get("geonames") or []
    if not geonames:
        return None
    # 有坐标则选最近的
    if lat is not None and lng is not None:
        best = None
        best_d = 1e18
        for g in geonames:
            try:
                glat = float(g.get("lat"))
                glng = float(g.get("lng"))
            except (TypeError, ValueError):
                continue
            d = (glat - float(lat)) ** 2 + (glng - float(lng)) ** 2
            if d < best_d:
                best_d = d
                best = g
        if best:
            return int(best["geonameId"])
    return int(geonames[0]["geonameId"])


def fetch_children(user: str, geoname_id: int) -> List[dict]:
    data = api_get("/childrenJSON", {
        "geonameId": geoname_id,
        "maxRows": 1000,
    }, user)
    return data.get("geonames") or []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--countries", default="", help="ISO2 列表，空=全部缺 L4 的国家")
    ap.add_argument("--limit-cities", type=int, default=0, help="每国最多处理多少 L3，0=不限")
    ap.add_argument("--sleep", type=float, default=1.1, help="请求间隔秒（免费账号约 1次/秒）")
    ap.add_argument("--user", default=os.environ.get("GEONAMES_USER", ""))
    args = ap.parse_args()

    user = (args.user or "").strip()
    if not user:
        print("需要 GeoNames 用户名：export GEONAMES_USER=xxx")
        print("注册：https://www.geonames.org/login")
        return 1

    only = {x.strip().upper() for x in args.countries.split(",") if x.strip()} or None

    hr("L4 by city API", "=")
    print(f"  batch: {BATCH}  user={user}  mode={'APPLY' if args.apply else 'DRY-RUN'}")

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()

    # 已有 GEONAMES L4
    cur.execute("SELECT source_id FROM geo_region WHERE source='GEONAMES' AND level=4 AND source_id IS NOT NULL")
    existed: Set[str] = {str(r[0]) for r in cur.fetchall()}

    # 缺 L4 的国家：有 L3 且（非 CN/VN）
    sql = """
        SELECT r.id, r.country_code, r.name, r.name_en, r.path, r.level, r.latitude, r.longitude
        FROM geo_region r
        WHERE r.status=1 AND r.level=3
          AND r.country_code NOT IN ('CN','VN')
    """
    params: list = []
    if only:
        ph = ",".join(["%s"] * len(only))
        sql += f" AND r.country_code IN ({ph})"
        params.extend(sorted(only))
    sql += " ORDER BY r.country_code, r.id"
    cur.execute(sql, params)
    cities = cur.fetchall()

    # 按国截断
    by_cc: Dict[str, list] = {}
    for row in cities:
        cc = row[1]
        by_cc.setdefault(cc, []).append(row)
    if args.limit_cities > 0:
        for cc in list(by_cc):
            by_cc[cc] = by_cc[cc][: args.limit_cities]

    cur.execute("SELECT COALESCE(MAX(id),0) FROM geo_region")
    next_id = int(cur.fetchone()[0]) + 1

    to_insert = []
    api_calls = 0
    for cc, rows in sorted(by_cc.items()):
        print(f"\n[{cc}] cities={len(rows)}")
        for rid, country_code, name, name_en, path, level, lat, lng in rows:
            display = name_en or name
            try:
                gid = search_city_geoname_id(user, country_code, display, lat, lng)
                api_calls += 1
                time.sleep(args.sleep)
                if not gid:
                    print(f"  miss city map: {display}")
                    continue
                children = fetch_children(user, gid)
                api_calls += 1
                time.sleep(args.sleep)
            except Exception as e:
                print(f"  API error @ {display}: {e}")
                time.sleep(args.sleep * 2)
                continue

            added = 0
            parent_path = path if path.endswith("/") else path + "/"
            for ch in children:
                # 只要行政区/下级地名；优先 A 类
                fcl = ch.get("fcl") or ""
                sid = str(ch.get("geonameId") or "")
                if not sid or sid in existed:
                    continue
                # 子节点名称
                cname = ch.get("name") or ch.get("toponymName")
                if not cname:
                    continue
                new_id = next_id
                next_id += 1
                to_insert.append({
                    "id": new_id,
                    "parent_id": int(rid),
                    "country_code": country_code,
                    "name": cname,
                    "name_en": ch.get("name") or cname,
                    "name_ch": None,
                    "code": None,
                    "level": int(level) + 1,
                    "region_type": "DISTRICT",
                    "path": f"{parent_path}{new_id}/",
                    "is_leaf": 1,
                    "latitude": float(ch["lat"]) if ch.get("lat") not in (None, "") else None,
                    "longitude": float(ch["lng"]) if ch.get("lng") not in (None, "") else None,
                    "source": "GEONAMES",
                    "source_id": sid,
                    "status": 1,
                    "sort": 0,
                })
                existed.add(sid)
                added += 1
            if added:
                print(f"  {display}: +{added} (geonameId={gid})")

    hr("汇总")
    print(f"  api_calls≈{api_calls}  pending insert={len(to_insert)}")
    for r in to_insert[:8]:
        print(f"    · {r['name']} → parent={r['parent_id']} {r['country_code']}")

    if not args.apply:
        print("DRY-RUN 结束。加 --apply 写库")
        conn.close()
        return 0
    if not to_insert:
        print("无数据")
        conn.close()
        return 0

    sql_ins = """
    INSERT INTO geo_region
      (id, parent_id, country_code, name, name_en, name_ch, code, level, region_type, path, is_leaf,
       latitude, longitude, source, source_id, status, sort, created_at, updated_at)
    VALUES
      (%(id)s,%(parent_id)s,%(country_code)s,%(name)s,%(name_en)s,%(name_ch)s,%(code)s,%(level)s,%(region_type)s,%(path)s,%(is_leaf)s,
       %(latitude)s,%(longitude)s,%(source)s,%(source_id)s,%(status)s,%(sort)s,NOW(),NOW())
    """
    conn.begin()
    try:
        cur.executemany(sql_ins, to_insert)
        cur.execute("""
            UPDATE geo_region r
            LEFT JOIN (
              SELECT parent_id, COUNT(*) cnt FROM geo_region WHERE status=1 GROUP BY parent_id
            ) c ON r.id=c.parent_id
            SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
        """)
        cur.execute("""
            UPDATE geo_country c SET max_level=(
              SELECT COALESCE(MAX(r.level),3) FROM geo_region r
              WHERE r.country_code=c.iso2 AND r.status=1
            ) WHERE c.iso2 NOT IN ('CN')
        """)
        conn.commit()
        print(f"已写入 {len(to_insert)} 条 L4")
    except Exception as e:
        conn.rollback()
        print("失败:", e)
        raise
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

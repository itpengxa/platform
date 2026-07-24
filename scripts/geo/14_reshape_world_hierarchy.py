#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
14_reshape_world_hierarchy.py
=============================
把挂在省(L2)下的 L4/L5，尽量挂到同省最近市(L3)/区(L4)。
默认处理除 VN 外全部国家（VN 已用 08 脚本整理过）。

用法:
  python3 14_reshape_world_hierarchy.py
  python3 14_reshape_world_hierarchy.py --apply
  python3 14_reshape_world_hierarchy.py --apply --countries TH,CN,US
  python3 14_reshape_world_hierarchy.py --apply --include-vn
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, List, Optional, Set, Tuple

import pymysql

BATCH = f"WORLD_RESHAPE_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"
DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}


def hr(title: str = "", char: str = "─", width: int = 64) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print(char * left + f" {title} " + char * (pad - left))
    else:
        print(char * width)


def haversine_km(lat1, lon1, lat2, lon2) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def build_path(parent_path: str, node_id: int) -> str:
    base = parent_path if parent_path.endswith("/") else parent_path + "/"
    if not base.startswith("/"):
        base = "/" + base
    return f"{base}{node_id}/"


@dataclass
class Node:
    id: int
    parent_id: int
    country_code: str
    level: int
    name: str
    path: str
    lat: Optional[float]
    lng: Optional[float]


@dataclass
class Move:
    node_id: int
    to_parent: int
    new_path: str
    distance_km: float


def nearest(target: Node, cands: List[Node], max_km: float) -> Optional[Tuple[Node, float]]:
    if target.lat is None or target.lng is None:
        return None
    best = None
    for c in cands:
        if c.lat is None or c.lng is None or c.id == target.id:
            continue
        d = haversine_km(target.lat, target.lng, c.lat, c.lng)
        if d > max_km:
            continue
        if best is None or d < best[1]:
            best = (c, d)
    return best


def fetch(cur, sql, params=None) -> List[Node]:
    cur.execute(sql, params or ())
    out = []
    for r in cur.fetchall():
        out.append(Node(
            id=int(r[0]), parent_id=int(r[1]), country_code=(r[2] or "").upper(),
            level=int(r[3]), name=r[4] or "", path=r[5] or "",
            lat=float(r[6]) if r[6] is not None else None,
            lng=float(r[7]) if r[7] is not None else None,
        ))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--max-km", type=float, default=40.0)
    ap.add_argument("--countries", default="")
    ap.add_argument("--include-vn", action="store_true")
    args = ap.parse_args()

    only = {x.strip().upper() for x in args.countries.split(",") if x.strip()} or None

    hr("WORLD Hierarchy Reshape", "=")
    print(f"  batch: {BATCH}  mode={'APPLY' if args.apply else 'DRY-RUN'}  max-km={args.max_km}")

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()

    provinces = fetch(cur, """
        SELECT id, parent_id, country_code, level, name, path, latitude, longitude
        FROM geo_region WHERE status=1 AND level=2
    """)
    if only:
        provinces = [p for p in provinces if p.country_code in only]
    if not args.include_vn:
        provinces = [p for p in provinces if p.country_code != "VN"]
    prov_ids = [p.id for p in provinces]
    print(f"  provinces in scope: {len(prov_ids):,}")
    if not prov_ids:
        print("无省可处理")
        return 1

    # chunk IN lists
    cities_by_prov: Dict[int, List[Node]] = defaultdict(list)
    districts_flat: List[Node] = []
    wards_flat: List[Node] = []

    def load_children(level: int) -> List[Node]:
        rows: List[Node] = []
        chunk = 800
        for i in range(0, len(prov_ids), chunk):
            part = prov_ids[i:i + chunk]
            ph = ",".join(["%s"] * len(part))
            rows.extend(fetch(cur, f"""
                SELECT id, parent_id, country_code, level, name, path, latitude, longitude
                FROM geo_region
                WHERE status=1 AND level=%s AND parent_id IN ({ph})
            """, [level, *part]))
        return rows

    cities = load_children(3)
    districts_flat = load_children(4)
    wards_flat = load_children(5)
    for c in cities:
        cities_by_prov[c.parent_id].append(c)

    print(f"  cities under provinces: {len(cities):,}")
    print(f"  L4 flat under provinces: {len(districts_flat):,}")
    print(f"  L5 flat under provinces: {len(wards_flat):,}")

    l4_moves: List[Move] = []
    l4_skip = 0
    district_after: Dict[int, Node] = {}
    districts_by_prov: Dict[int, List[Node]] = defaultdict(list)

    for d in districts_flat:
        cands = cities_by_prov.get(d.parent_id, [])
        hit = nearest(d, cands, args.max_km) if cands else None
        if not hit:
            l4_skip += 1
            district_after[d.id] = d
            districts_by_prov[d.parent_id].append(d)
            continue
        city, dist = hit
        nd = Node(d.id, city.id, d.country_code, 4, d.name, build_path(city.path, d.id), d.lat, d.lng)
        district_after[d.id] = nd
        districts_by_prov[d.parent_id].append(nd)
        l4_moves.append(Move(d.id, city.id, nd.path, dist))

    l5_moves: List[Move] = []
    l5_skip = 0
    for w in wards_flat:
        cands = districts_by_prov.get(w.parent_id, [])
        hit = nearest(w, cands, args.max_km) if cands else None
        if not hit:
            l5_skip += 1
            continue
        dist_node, dist = hit
        parent = district_after.get(dist_node.id, dist_node)
        l5_moves.append(Move(w.id, parent.id, build_path(parent.path, w.id), dist))

    print(f"  L4 moves={len(l4_moves):,} skip={l4_skip:,}")
    print(f"  L5 moves={len(l5_moves):,} skip={l5_skip:,}")

    if not args.apply:
        print("DRY-RUN 完成。加 --apply 写库。")
        conn.close()
        return 0

    conn.begin()
    try:
        sql = "UPDATE geo_region SET parent_id=%s, path=%s, updated_at=NOW() WHERE id=%s"
        for i in range(0, len(l4_moves), 800):
            batch = l4_moves[i:i + 800]
            cur.executemany(sql, [(m.to_parent, m.new_path, m.node_id) for m in batch])
        for i in range(0, len(l5_moves), 800):
            batch = l5_moves[i:i + 800]
            cur.executemany(sql, [(m.to_parent, m.new_path, m.node_id) for m in batch])
        cur.execute("""
            UPDATE geo_region r
            LEFT JOIN (
              SELECT parent_id, COUNT(*) cnt FROM geo_region WHERE status=1 GROUP BY parent_id
            ) c ON r.id=c.parent_id
            SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
        """)
        cur.execute("""
            UPDATE geo_country c
            SET max_level = (
              SELECT COALESCE(MAX(r.level), 3) FROM geo_region r
              WHERE r.country_code=c.iso2 AND r.status=1
            )
        """)
        conn.commit()
        print("写库完成。请清 Redis platform:geo:*")
    except Exception as e:
        conn.rollback()
        print("失败:", e)
        raise
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

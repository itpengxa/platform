#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
08_reshape_vn_hierarchy.py
==========================
目的
  把目前「扁平挂在省(L2)下」的 L4/L5，尽量挂到同省最近的市(L3)/区(L4)，
  让级联下钻变成：国家 → 省 → 市 → 区 → 街道，排版清晰。

策略
  L4（挂在省下）→ 同省内球面距离最近的 L3 市（阈值 max-km）
  L5（挂在省下）→ 同省内最近的 L4（优先已挂到市下的区；阈值 max-km）
  超距 / 无候选 → 保留原 parent，计入 skip

用法
  python3 08_reshape_vn_hierarchy.py                  # 只预览，不写库
  python3 08_reshape_vn_hierarchy.py --apply          # 写库
  python3 08_reshape_vn_hierarchy.py --apply --max-km 40
  python3 08_reshape_vn_hierarchy.py --province-id 3807   # 只处理太原省

环境变量（可选）
  MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DB
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

import pymysql

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}

COUNTRY = "VN"
BATCH = f"VN_RESHAPE_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def hr(title: str = "", char: str = "─", width: int = 64) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print(char * left + f" {title} " + char * (pad - left))
    else:
        print(char * width)


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
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
    level: int
    name: str
    path: str
    lat: Optional[float]
    lng: Optional[float]


@dataclass
class Move:
    node_id: int
    name: str
    from_parent: int
    to_parent: int
    to_parent_name: str
    distance_km: float
    new_path: str


# ---------------------------------------------------------------------------
# 读库
# ---------------------------------------------------------------------------

def fetch_nodes(cur, level: int, province_id: Optional[int] = None) -> List[Node]:
    sql = """
        SELECT id, parent_id, level, name, path, latitude, longitude
        FROM geo_region
        WHERE country_code=%s AND status=1 AND level=%s
    """
    params: list = [COUNTRY, level]
    if province_id is not None and level == 2:
        sql += " AND id=%s"
        params.append(province_id)
    cur.execute(sql, params)
    rows = []
    for r in cur.fetchall():
        rows.append(Node(
            id=int(r[0]),
            parent_id=int(r[1]),
            level=int(r[2]),
            name=r[3] or "",
            path=r[4] or "",
            lat=float(r[5]) if r[5] is not None else None,
            lng=float(r[6]) if r[6] is not None else None,
        ))
    return rows


def fetch_children_of_provinces(cur, province_ids: List[int], level: int) -> List[Node]:
    if not province_ids:
        return []
    placeholders = ",".join(["%s"] * len(province_ids))
    cur.execute(
        f"""
        SELECT id, parent_id, level, name, path, latitude, longitude
        FROM geo_region
        WHERE country_code=%s AND status=1 AND level=%s
          AND parent_id IN ({placeholders})
        """,
        [COUNTRY, level, *province_ids],
    )
    out = []
    for r in cur.fetchall():
        out.append(Node(
            id=int(r[0]),
            parent_id=int(r[1]),
            level=int(r[2]),
            name=r[3] or "",
            path=r[4] or "",
            lat=float(r[5]) if r[5] is not None else None,
            lng=float(r[6]) if r[6] is not None else None,
        ))
    return out


def hierarchy_stats(cur) -> List[Tuple]:
    cur.execute(
        """
        SELECT child.level,
               parent.level AS parent_level,
               COUNT(*) AS cnt
        FROM geo_region child
        JOIN geo_region parent ON parent.id = child.parent_id
        WHERE child.country_code=%s AND child.status=1 AND child.level > 1
        GROUP BY child.level, parent.level
        ORDER BY child.level, parent.level
        """,
        (COUNTRY,),
    )
    return cur.fetchall()


def print_stats(title: str, rows: List[Tuple]) -> None:
    hr(title)
    print(f"{'子级level':>10}  {'父级level':>10}  {'数量':>10}  说明")
    print(f"{'-'*10}  {'-'*10}  {'-'*10}  {'-'*24}")
    tip = {
        (3, 2): "市 → 省（正常）",
        (4, 2): "区 → 省（扁平，待整理）",
        (4, 3): "区 → 市（清晰）",
        (5, 2): "街道 → 省（扁平，待整理）",
        (5, 3): "街道 → 市",
        (5, 4): "街道 → 区（清晰）",
        (2, 1): "省 → 国家（正常）",
    }
    for lv, plv, cnt in rows:
        print(f"{lv:>10}  {plv:>10}  {cnt:>10,}  {tip.get((lv, plv), '')}")
    print()


# ---------------------------------------------------------------------------
# 匹配
# ---------------------------------------------------------------------------

def nearest(
    target: Node,
    candidates: List[Node],
    max_km: float,
) -> Optional[Tuple[Node, float]]:
    if target.lat is None or target.lng is None:
        return None
    best: Optional[Tuple[Node, float]] = None
    for c in candidates:
        if c.lat is None or c.lng is None:
            continue
        if c.id == target.id:
            continue
        d = haversine_km(target.lat, target.lng, c.lat, c.lng)
        if d > max_km:
            continue
        if best is None or d < best[1]:
            best = (c, d)
    return best


def plan_l4_moves(
    provinces: List[Node],
    cities_by_province: Dict[int, List[Node]],
    districts: List[Node],
    max_km: float,
) -> Tuple[List[Move], Dict[str, int]]:
    prov_map = {p.id: p for p in provinces}
    moves: List[Move] = []
    skip = defaultdict(int)

    for d in districts:
        prov = prov_map.get(d.parent_id)
        if prov is None:
            skip["parent_not_province"] += 1
            continue
        cities = cities_by_province.get(prov.id, [])
        if not cities:
            skip["no_city_in_province"] += 1
            continue
        hit = nearest(d, cities, max_km)
        if hit is None:
            skip["over_max_km_or_no_coords"] += 1
            continue
        city, dist = hit
        moves.append(Move(
            node_id=d.id,
            name=d.name,
            from_parent=d.parent_id,
            to_parent=city.id,
            to_parent_name=city.name,
            distance_km=dist,
            new_path=build_path(city.path, d.id),
        ))
    return moves, dict(skip)


def plan_l5_moves(
    provinces: List[Node],
    districts_after: Dict[int, Node],  # id -> node with updated parent/path intent
    districts_by_province: Dict[int, List[Node]],
    wards: List[Node],
    max_km: float,
) -> Tuple[List[Move], Dict[str, int]]:
    """L5 仍挂在省下时，挂到同省最近 L4。"""
    prov_ids = {p.id for p in provinces}
    moves: List[Move] = []
    skip = defaultdict(int)

    for w in wards:
        if w.parent_id not in prov_ids:
            skip["already_not_under_province"] += 1
            continue
        cands = districts_by_province.get(w.parent_id, [])
        # 用计划后的 path/parent（若 L4 已规划搬家）
        resolved = []
        for d in cands:
            nd = districts_after.get(d.id, d)
            resolved.append(nd)
        if not resolved:
            skip["no_district_in_province"] += 1
            continue
        hit = nearest(w, resolved, max_km)
        if hit is None:
            skip["over_max_km_or_no_coords"] += 1
            continue
        dist_node, dist = hit
        # path 基于搬家后的区 path
        parent_path = districts_after.get(dist_node.id, dist_node).path
        if dist_node.id in districts_after:
            parent_path = districts_after[dist_node.id].path
        moves.append(Move(
            node_id=w.id,
            name=w.name,
            from_parent=w.parent_id,
            to_parent=dist_node.id,
            to_parent_name=dist_node.name,
            distance_km=dist,
            new_path=build_path(parent_path, w.id),
        ))
    return moves, dict(skip)


# ---------------------------------------------------------------------------
# 写库
# ---------------------------------------------------------------------------

def apply_moves(cur, moves: List[Move], chunk: int = 500) -> int:
    sql = "UPDATE geo_region SET parent_id=%s, path=%s, updated_at=NOW() WHERE id=%s AND country_code=%s"
    n = 0
    for i in range(0, len(moves), chunk):
        batch = moves[i:i + chunk]
        cur.executemany(sql, [(m.to_parent, m.new_path, m.node_id, COUNTRY) for m in batch])
        n += len(batch)
    return n


def rebuild_is_leaf(cur) -> None:
    cur.execute(
        """
        UPDATE geo_region r
        LEFT JOIN (
            SELECT parent_id, COUNT(*) AS cnt
            FROM geo_region
            WHERE country_code=%s AND status=1
            GROUP BY parent_id
        ) c ON r.id = c.parent_id
        SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt = 0 THEN 1 ELSE 0 END
        WHERE r.country_code=%s
        """,
        (COUNTRY, COUNTRY),
    )


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="VN 区划层级重挂载（排版清晰）")
    parser.add_argument("--apply", action="store_true", help="真正写库；默认 dry-run")
    parser.add_argument("--max-km", type=float, default=40.0, help="最近邻最大距离 km（默认 40）")
    parser.add_argument("--province-id", type=int, default=None, help="只处理指定省 id")
    parser.add_argument("--sample", type=int, default=8, help="预览样例条数")
    args = parser.parse_args()

    hr("VN Hierarchy Reshape", "=")
    print(f"  batch     : {BATCH}")
    print(f"  mode      : {'APPLY（写库）' if args.apply else 'DRY-RUN（只预览）'}")
    print(f"  max-km    : {args.max_km}")
    print(f"  province  : {args.province_id or 'ALL'}")
    print(f"  db        : {DB_CONFIG['user']}@{DB_CONFIG['host']}/{DB_CONFIG['database']}")
    print()

    conn = pymysql.connect(**DB_CONFIG)
    try:
        cur = conn.cursor()

        before = hierarchy_stats(cur)
        print_stats("整理前 · 父子 level 分布", before)

        provinces = fetch_nodes(cur, 2, args.province_id)
        if args.province_id:
            provinces = [p for p in provinces if p.id == args.province_id]
        province_ids = [p.id for p in provinces]
        if not province_ids:
            print("未找到省节点，退出")
            return 1

        cities = fetch_children_of_provinces(cur, province_ids, 3)
        districts = fetch_children_of_provinces(cur, province_ids, 4)
        wards = fetch_children_of_provinces(cur, province_ids, 5)

        cities_by_prov: Dict[int, List[Node]] = defaultdict(list)
        for c in cities:
            cities_by_prov[c.parent_id].append(c)

        hr("加载摘要")
        print(f"  省(L2)           : {len(provinces):>8,}")
        print(f"  市(L3) 挂省下    : {len(cities):>8,}")
        print(f"  区(L4) 挂省下    : {len(districts):>8,}  ← 将尝试挂到市")
        print(f"  街道(L5) 挂省下  : {len(wards):>8,}  ← 将尝试挂到区")
        print()

        # ---- L4 → L3 ----
        hr("Step 1 / 3 · L4 → 最近 L3 市")
        l4_moves, l4_skip = plan_l4_moves(provinces, cities_by_prov, districts, args.max_km)
        print(f"  可挂载 : {len(l4_moves):>8,}")
        print(f"  跳过   : {sum(l4_skip.values()):>8,}  {dict(l4_skip)}")
        if l4_moves:
            dists = sorted(m.distance_km for m in l4_moves)
            print(f"  距离km : min={dists[0]:.1f}  p50={dists[len(dists)//2]:.1f}  max={dists[-1]:.1f}")
            print(f"  样例   :")
            for m in l4_moves[: args.sample]:
                print(f"    · {m.name[:28]:<28} → {m.to_parent_name[:20]:<20}  {m.distance_km:5.1f}km")
        print()

        # 规划后的 L4 节点（供 L5 用 path）
        districts_after: Dict[int, Node] = {}
        move_by_id = {m.node_id: m for m in l4_moves}
        all_l4_in_scope = fetch_children_of_provinces(cur, province_ids, 4)
        # also include L4 already under cities? for L5 matching we need all L4 under these provinces' trees
        # For now only L4 currently under province; after move they still belong to province geographically
        districts_by_prov: Dict[int, List[Node]] = defaultdict(list)
        for d in districts:
            m = move_by_id.get(d.id)
            if m:
                nd = Node(d.id, m.to_parent, 4, d.name, m.new_path, d.lat, d.lng)
            else:
                nd = d
            districts_after[d.id] = nd
            districts_by_prov[d.parent_id].append(nd)  # group by original province id

        # ---- L5 → L4 ----
        hr("Step 2 / 3 · L5 → 最近 L4 区")
        l5_moves, l5_skip = plan_l5_moves(
            provinces, districts_after, districts_by_prov, wards, args.max_km
        )
        # fix new_path using districts_after path
        for m in l5_moves:
            parent = districts_after.get(m.to_parent)
            if parent:
                m.new_path = build_path(parent.path, m.node_id)
                m.to_parent_name = parent.name

        print(f"  可挂载 : {len(l5_moves):>8,}")
        print(f"  跳过   : {sum(l5_skip.values()):>8,}  {dict(l5_skip)}")
        if l5_moves:
            dists = sorted(m.distance_km for m in l5_moves)
            print(f"  距离km : min={dists[0]:.1f}  p50={dists[len(dists)//2]:.1f}  max={dists[-1]:.1f}")
            print(f"  样例   :")
            for m in l5_moves[: args.sample]:
                print(f"    · {m.name[:28]:<28} → {m.to_parent_name[:20]:<20}  {m.distance_km:5.1f}km")
        print()

        # ---- apply ----
        hr("Step 3 / 3 · 写库 / is_leaf")
        if not args.apply:
            print("  DRY-RUN：未写库。确认无误后加 --apply")
            print()
            print("  预期级联：国家 → 省 → 市 → 区 → 街道")
            print("  写库后请清理缓存：")
            print("    redis-cli EVAL \"local k=redis.call('keys',ARGV[1]) for i=1,#k do redis.call('del',k[i]) end return #k\" 0 'platform:geo:*'")
            return 0

        conn.begin()
        try:
            n4 = apply_moves(cur, l4_moves)
            n5 = apply_moves(cur, l5_moves)
            rebuild_is_leaf(cur)
            conn.commit()
            print(f"  已更新 L4 parent/path : {n4:,}")
            print(f"  已更新 L5 parent/path : {n5:,}")
            print(f"  已重建 is_leaf")
        except Exception as e:
            conn.rollback()
            print(f"  失败已回滚: {e}")
            return 2

        after = hierarchy_stats(cur)
        print()
        print_stats("整理后 · 父子 level 分布", after)
        print("  请清理 Redis 前缀 platform:geo:* 后重新验证级联。")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())

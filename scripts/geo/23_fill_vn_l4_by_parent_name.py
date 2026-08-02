#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
23_fill_vn_l4_by_parent_name.py
================================
简单逻辑（不把 OSM admin_level 映射成我们的 L4/L5…）:
  1) 取 OSM 对象 a：只要不是国家/省/市，任意 place 层级都可
  2) a 落在名称=本库 L3 c 的行政界内 → 父级匹配
  3) c 下还没有同名 / 同 source_id 的 a → 写入本库 L4
  4) id ≥ 4000000（实际 max(现有L4)+1）
  5) 重建 path；有经纬度则写入

实现（本地 PBF，不依赖 Overpass）:
  vietnam-latest.osm.pbf
  → 行政界 name 对齐 L3
  → 界内 place 节点写入 L4

用法:
  python3 23_fill_vn_l4_by_parent_name.py --limit 50
  python3 23_fill_vn_l4_by_parent_name.py --apply --empty-only
  python3 23_fill_vn_l4_by_parent_name.py --apply
"""

from __future__ import annotations

import argparse
import math
import os
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional, Set, Tuple

import osmium
import osmium.geom
import pymysql
from shapely import wkt as shapely_wkt
from shapely.geometry import Point
from shapely.strtree import STRtree

PBF = os.environ.get(
    "VN_OSM_PBF",
    os.path.join(os.path.dirname(__file__), "raw/osm/vietnam-latest.osm.pbf"),
)
COUNTRY = "VN"
ID_FLOOR = 4_000_000
BATCH = f"VN_L4_PARENT_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"
SKIP_PLACE = {"country", "state", "province", "city", "county", "district"}

DB = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}

CHAR_MAP = {"Đ": "D", "đ": "d"}


def strip_diacritics(s: str) -> str:
    if not s:
        return ""
    s = "".join(CHAR_MAP.get(ch, ch) for ch in s)
    nfkd = unicodedata.normalize("NFKD", s)
    return re.sub(r"[^a-zA-Z0-9 ]", "", nfkd).strip().lower()


def norm_name(s: str) -> str:
    return re.sub(r"\s+", " ", strip_diacritics(s or "")).strip()


def build_path(parent_path: str, node_id: int) -> str:
    base = parent_path if parent_path.endswith("/") else parent_path + "/"
    if not base.startswith("/"):
        base = "/" + base
    return f"{base}{node_id}/"


def is_same_as_l3(child_name: str, l3_name: str) -> bool:
    cn, ln = norm_name(child_name), norm_name(l3_name)
    if not cn or cn == ln:
        return True
    for p in ("xa ", "phuong ", "thi tran ", "thi xa "):
        if ln.startswith(p) and cn == ln[len(p):]:
            return True
        if cn.startswith(p) and ln == cn[len(p):]:
            return True
    return False


@dataclass
class L3:
    id: int
    name: str
    path: str
    province: str
    lat: Optional[float]
    lng: Optional[float]
    existing_names: Set[str] = field(default_factory=set)
    existing_sids: Set[str] = field(default_factory=set)


@dataclass
class Child:
    source_id: str
    name: str
    lat: Optional[float]
    lng: Optional[float]
    place: str


@dataclass
class Planned:
    l3_id: int
    l3_name: str
    child: Child


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def pick_l3(cands: List[L3], lat: float, lng: float) -> L3:
    if len(cands) == 1:
        return cands[0]
    best, best_d = cands[0], 1e18
    for c in cands:
        if c.lat is None or c.lng is None:
            continue
        d = haversine_km(lat, lng, c.lat, c.lng)
        if d < best_d:
            best, best_d = c, d
    return best


def load_l3_polygons(l3_by_norm: Dict[str, List[L3]], pbf: str) -> Tuple[List, List[int]]:
    """行政界 name ↔ L3 → (geoms, l3_ids) 对齐列表，供 STRtree。"""
    wkt_factory = osmium.geom.WKTFactory()
    geoms = []
    l3_ids: List[int] = []
    seen_l3: Set[int] = set()
    matched = 0
    skipped = 0

    class AreaH(osmium.SimpleHandler):
        def area(self, a):
            nonlocal matched, skipped
            if a.tags.get("boundary") != "administrative":
                return
            # 跳过国家/省（admin_level 2）
            al = a.tags.get("admin_level") or ""
            if al in {"1", "2"}:
                return
            name = (a.tags.get("name") or a.tags.get("name:vi") or "").strip()
            if not name:
                return
            nn = norm_name(name)
            cands = l3_by_norm.get(nn)
            if not cands:
                return
            try:
                wkt = wkt_factory.create_multipolygon(a)
            except Exception:
                skipped += 1
                return
            if not wkt:
                skipped += 1
                return
            try:
                geom = shapely_wkt.loads(wkt)
            except Exception:
                skipped += 1
                return
            if geom.is_empty:
                skipped += 1
                return
            cen = geom.representative_point()
            l3 = pick_l3(cands, cen.y, cen.x)
            if l3.id in seen_l3:
                # 同名多面：保留面积更大的
                idx = l3_ids.index(l3.id)
                if geom.area <= geoms[idx].area:
                    return
                geoms[idx] = geom
                return
            seen_l3.add(l3.id)
            geoms.append(geom)
            l3_ids.append(l3.id)
            matched += 1

    print(f"scan admin areas from {pbf} …")
    AreaH().apply_file(pbf, locations=True)
    print(f"  L3 polygons matched={matched} skipped_geom={skipped}")
    return geoms, l3_ids


def collect_places(
    geoms,
    l3_ids: List[int],
    l3_map: Dict[int, L3],
    pbf: str,
) -> List[Planned]:
    tree = STRtree(geoms)
    planned: List[Planned] = []
    seen_global_sid: Set[str] = set()
    stats = defaultdict(int)

    class PlaceH(osmium.SimpleHandler):
        def node(self, n):
            place = n.tags.get("place") or ""
            if not place or place in SKIP_PLACE:
                return
            name = (n.tags.get("name") or n.tags.get("name:vi") or "").strip()
            if not name:
                return
            nn = norm_name(name)
            if "thanh pho" in nn or nn.startswith("tinh "):
                return
            try:
                lat, lng = float(n.location.lat), float(n.location.lon)
            except Exception:
                return
            if not (8.0 <= lat <= 24.0 and 102.0 <= lng <= 110.5):
                return

            pt = Point(lng, lat)
            idxs = tree.query(pt)
            hit_l3 = None
            for i in idxs:
                if geoms[int(i)].covers(pt) or geoms[int(i)].intersects(pt):
                    hit_l3 = l3_map.get(l3_ids[int(i)])
                    if hit_l3:
                        break
            if not hit_l3:
                stats["no_parent"] += 1
                return
            if is_same_as_l3(name, hit_l3.name):
                stats["same_as_l3"] += 1
                return
            sid = f"node{n.id}"
            if sid in seen_global_sid or sid in hit_l3.existing_sids:
                stats["dup_sid"] += 1
                return
            if nn in hit_l3.existing_names:
                stats["dup_name"] += 1
                return
            seen_global_sid.add(sid)
            hit_l3.existing_names.add(nn)
            hit_l3.existing_sids.add(sid)
            planned.append(Planned(
                l3_id=hit_l3.id,
                l3_name=hit_l3.name,
                child=Child(source_id=sid, name=name[:256], lat=lat, lng=lng, place=place),
            ))
            stats["ok"] += 1

    print(f"scan place nodes from {pbf} …")
    PlaceH().apply_file(pbf, locations=True)
    print(f"  place stats: {dict(stats)}")
    return planned


def main() -> int:
    ap = argparse.ArgumentParser(description="父级名匹配 L3 → 写入 L4（本地 PBF）")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--workers", type=int, default=10, help="保留参数（本地扫描单进程）")
    ap.add_argument("--limit", type=int, default=0, help="最多写入 N 条（调试）")
    ap.add_argument("--empty-only", action="store_true", help="只填当前无 L4 子级的 L3")
    ap.add_argument("--province-id", type=int, default=None)
    ap.add_argument("--sample", type=int, default=15)
    ap.add_argument("--pbf", default=None)
    args = ap.parse_args()

    pbf = args.pbf or PBF
    if not os.path.isfile(pbf):
        print(f"missing PBF: {pbf}")
        return 1

    print(f"batch={BATCH} mode={'APPLY' if args.apply else 'DRY-RUN'} pbf={pbf}")

    conn = pymysql.connect(**DB)
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT l3.id, l3.name, l3.path, l2.name, l3.latitude, l3.longitude
            FROM geo_region l3
            JOIN geo_region l2 ON l2.id = l3.parent_id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1
            ORDER BY l3.id
            """,
            (COUNTRY,),
        )
        l3_list: List[L3] = []
        for r in cur.fetchall():
            l3_list.append(L3(
                id=int(r[0]), name=r[1] or "", path=r[2] or "",
                province=r[3] or "",
                lat=float(r[4]) if r[4] is not None else None,
                lng=float(r[5]) if r[5] is not None else None,
            ))

        if args.province_id:
            cur.execute(
                "SELECT id FROM geo_region WHERE country_code=%s AND level=3 AND parent_id=%s",
                (COUNTRY, args.province_id),
            )
            allow = {int(x[0]) for x in cur.fetchall()}
            l3_list = [x for x in l3_list if x.id in allow]

        l3_map = {x.id: x for x in l3_list}
        l3_by_norm: Dict[str, List[L3]] = defaultdict(list)
        for x in l3_list:
            l3_by_norm[norm_name(x.name)].append(x)

        cur.execute(
            """
            SELECT parent_id, name, source_id FROM geo_region
            WHERE country_code=%s AND level=4 AND status=1
            """,
            (COUNTRY,),
        )
        for pid, name, sid in cur.fetchall():
            pid = int(pid)
            if pid in l3_map:
                l3_map[pid].existing_names.add(norm_name(name or ""))
                if sid:
                    l3_map[pid].existing_sids.add(sid)

        if args.empty_only:
            keep = {x.id for x in l3_list if not x.existing_names}
            l3_list = [x for x in l3_list if x.id in keep]
            l3_map = {x.id: x for x in l3_list}
            l3_by_norm = defaultdict(list)
            for x in l3_list:
                l3_by_norm[norm_name(x.name)].append(x)
            print(f"empty L3 only: {len(l3_list)}")
        else:
            print(f"L3 total: {len(l3_list)}")

        geoms, geom_l3_ids = load_l3_polygons(l3_by_norm, pbf)
        if not geoms:
            print("no L3 polygons matched")
            return 1

        if args.empty_only:
            allow = set(l3_map)
            filtered = [(g, i) for g, i in zip(geoms, geom_l3_ids) if i in allow]
            geoms = [x[0] for x in filtered]
            geom_l3_ids = [x[1] for x in filtered]
            print(f"  polygons for empty L3: {len(geoms)}")

        planned = collect_places(geoms, geom_l3_ids, l3_map, pbf)

        if args.limit > 0:
            planned = planned[: args.limit]

        print(f"to insert: {len(planned)}")
        print(f"cover L3: {len({p.l3_id for p in planned})}")
        for p in planned[: args.sample]:
            c = p.child
            print(f"  · {c.name[:28]:<28} → {p.l3_name[:22]:<22} [{c.place}] {c.source_id}")

        if not args.apply:
            print("DRY-RUN：未写库。加 --apply 写入")
            return 0
        if not planned:
            print("无可写入")
            return 0

        cur.execute("SELECT COALESCE(MAX(id), %s - 1) FROM geo_region WHERE level=4", (ID_FLOOR,))
        next_id = max(int(cur.fetchone()[0]) + 1, ID_FLOOR)
        l3_path = {x.id: x.path for x in l3_map.values()}

        rows = []
        for p in planned:
            rid = next_id
            next_id += 1
            c = p.child
            rows.append({
                "id": rid,
                "parent_id": p.l3_id,
                "country_code": COUNTRY,
                "name": c.name,
                "name_en": None,
                "name_ch": None,
                "code": None,
                "level": 4,
                "region_type": (c.place or "PLACE").upper()[:32],
                "path": build_path(l3_path[p.l3_id], rid),
                "is_leaf": 1,
                "latitude": c.lat,
                "longitude": c.lng,
                "source": "OSM",
                "source_id": c.source_id,
                "status": 1,
                "sort": 0,
            })

        sql = """
            INSERT INTO geo_region
              (id, parent_id, country_code, name, name_en, name_ch, code, level, region_type,
               path, is_leaf, latitude, longitude, source, source_id, status, sort)
            VALUES
              (%(id)s,%(parent_id)s,%(country_code)s,%(name)s,%(name_en)s,%(name_ch)s,%(code)s,
               %(level)s,%(region_type)s,%(path)s,%(is_leaf)s,%(latitude)s,%(longitude)s,
               %(source)s,%(source_id)s,%(status)s,%(sort)s)
        """
        conn.begin()
        try:
            for i in range(0, len(rows), 500):
                cur.executemany(sql, rows[i:i + 500])
            cur.execute(
                """
                UPDATE geo_region r
                LEFT JOIN (
                  SELECT parent_id, COUNT(*) cnt FROM geo_region
                  WHERE country_code=%s AND status=1 GROUP BY parent_id
                ) c ON r.id=c.parent_id
                SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
                WHERE r.country_code=%s AND r.level IN (3,4)
                """,
                (COUNTRY, COUNTRY),
            )
            conn.commit()
            print(f"inserted: {len(rows)}")
        except Exception as e:
            conn.rollback()
            print(f"rollback: {e}")
            return 2

        cur.execute(
            """
            SELECT
              SUM(CASE WHEN c.cnt>0 THEN 1 ELSE 0 END),
              SUM(CASE WHEN COALESCE(c.cnt,0)=0 THEN 1 ELSE 0 END)
            FROM geo_region l3
            LEFT JOIN (
              SELECT parent_id, COUNT(*) cnt FROM geo_region
              WHERE country_code=%s AND level=4 AND status=1 GROUP BY parent_id
            ) c ON c.parent_id=l3.id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1
            """,
            (COUNTRY, COUNTRY),
        )
        print("L3 with/without L4:", cur.fetchone())
        print("请清理 Redis: platform:geo:*")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())

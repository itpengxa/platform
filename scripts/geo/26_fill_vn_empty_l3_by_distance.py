#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
26_fill_vn_empty_l3_by_distance.py
==================================
名称匹配补不上的空 L3，按距离补一部分 L4。

规则:
  1) 只处理当前无 L4 子级的 L3
  2) GeoNames PPL* + OSM place（非国家/省/市）
  3) 点到最近 L3；若最近的就是该空 L3 且距离 ≤ max-km → 写入 L4
  4) 跳过与 L3 同名；同 L3 下同名/同 source_id 去重
  5) 重建 path；有经纬度写入

用法:
  python3 26_fill_vn_empty_l3_by_distance.py
  python3 26_fill_vn_empty_l3_by_distance.py --apply --max-km 5
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
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import osmium
import pymysql

ROOT = Path(__file__).resolve().parent
PBF = Path(os.environ.get("VN_OSM_PBF", str(ROOT / "raw/osm/vietnam-latest.osm.pbf")))
GN_FILE = Path(os.environ.get("VN_GEONAMES", str(ROOT / "raw/VN.txt")))
COUNTRY = "VN"
ID_FLOOR = 4_000_000
BATCH = f"VN_L4_DIST_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"
USE_FCODES = {"PPL", "PPLA3", "PPLA4", "PPLL", "PPLX", "PPLQ", "LCTY"}
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
    for p in ("xa ", "phuong ", "thi tran ", "thi xa ", "dac khu "):
        if ln.startswith(p) and cn == ln[len(p):]:
            return True
        if cn.startswith(p) and ln == cn[len(p):]:
            return True
    return False


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


@dataclass
class L3:
    id: int
    name: str
    path: str
    lat: float
    lng: float
    empty: bool
    existing_names: Set[str] = field(default_factory=set)
    existing_sids: Set[str] = field(default_factory=set)


@dataclass
class Cand:
    source: str
    source_id: str
    name: str
    lat: float
    lng: float
    kind: str


@dataclass
class Planned:
    l3: L3
    cand: Cand
    dist_km: float


def main() -> int:
    ap = argparse.ArgumentParser(description="空 L3 按距离补 L4")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--max-km", type=float, default=5.0)
    ap.add_argument("--sample", type=int, default=20)
    ap.add_argument("--pbf", default=str(PBF))
    ap.add_argument("--geonames", default=str(GN_FILE))
    args = ap.parse_args()

    print(f"batch={BATCH} mode={'APPLY' if args.apply else 'DRY-RUN'} max_km={args.max_km}")

    conn = pymysql.connect(**DB)
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT l3.id, l3.name, l3.path, l3.latitude, l3.longitude,
                   CASE WHEN c.parent_id IS NULL THEN 1 ELSE 0 END AS is_empty
            FROM geo_region l3
            LEFT JOIN (
              SELECT parent_id FROM geo_region
              WHERE country_code=%s AND level=4 AND status=1 GROUP BY parent_id
            ) c ON c.parent_id=l3.id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1
              AND l3.latitude IS NOT NULL AND l3.longitude IS NOT NULL
            """,
            (COUNTRY, COUNTRY),
        )
        all_l3: List[L3] = []
        empty_ids: Set[int] = set()
        for r in cur.fetchall():
            n = L3(
                id=int(r[0]), name=r[1] or "", path=r[2] or "",
                lat=float(r[3]), lng=float(r[4]), empty=bool(r[5]),
            )
            all_l3.append(n)
            if n.empty:
                empty_ids.add(n.id)
        print(f"L3 with coords: {len(all_l3)}, empty: {len(empty_ids)}")
        if not empty_ids:
            print("无空 L3")
            return 0

        l3_map = {x.id: x for x in all_l3}

        # 网格加速最近 L3
        cell = 20
        grid: Dict[Tuple[int, int], List[L3]] = defaultdict(list)
        for x in all_l3:
            grid[(int(x.lat * cell), int(x.lng * cell))].append(x)

        def nearest_l3(lat: float, lng: float) -> Optional[Tuple[L3, float]]:
            best: Optional[L3] = None
            best_d = 1e18
            ia, ib = int(lat * cell), int(lng * cell)
            for ring in range(0, 8):
                for di in range(-ring, ring + 1):
                    for dj in range(-ring, ring + 1):
                        if ring > 0 and abs(di) != ring and abs(dj) != ring:
                            continue
                        for n in grid.get((ia + di, ib + dj), []):
                            d = haversine_km(lat, lng, n.lat, n.lng)
                            if d < best_d:
                                best, best_d = n, d
                if best is not None and best_d <= args.max_km and ring >= 2:
                    break
            if best is None:
                return None
            return best, best_d

        cands: List[Cand] = []

        # GeoNames
        gn_path = Path(args.geonames)
        if gn_path.is_file():
            with gn_path.open(encoding="utf-8", errors="replace") as f:
                for line in f:
                    p = line.rstrip("\n").split("\t")
                    if len(p) < 8 or p[7] not in USE_FCODES:
                        continue
                    name = (p[1] or "").strip()
                    if not name:
                        continue
                    try:
                        lat, lng = float(p[4]), float(p[5])
                    except ValueError:
                        continue
                    cands.append(Cand("GEONAMES", str(p[0]), name[:256], lat, lng, p[7]))
            print(f"GeoNames candidates: {len(cands)}")

        # OSM places
        osm_before = len(cands)
        pbf = Path(args.pbf)

        class PlaceH(osmium.SimpleHandler):
            def node(self, n):
                place = n.tags.get("place") or ""
                if not place or place in SKIP_PLACE:
                    return
                name = (n.tags.get("name") or n.tags.get("name:vi") or "").strip()
                if not name:
                    return
                try:
                    lat, lng = float(n.location.lat), float(n.location.lon)
                except Exception:
                    return
                if not (8.0 <= lat <= 24.5 and 102.0 <= lng <= 118.0):
                    return
                cands.append(Cand("OSM", f"node{n.id}", name[:256], lat, lng, place.upper()))

        if pbf.is_file():
            print(f"scan OSM places from {pbf} …")
            PlaceH().apply_file(str(pbf), locations=True)
            print(f"OSM places added: {len(cands) - osm_before}")

        planned: List[Planned] = []
        cover: Set[int] = set()
        skip = defaultdict(int)
        seen_sid: Set[str] = set()

        for c in cands:
            hit = nearest_l3(c.lat, c.lng)
            if not hit:
                skip["no_l3"] += 1
                continue
            l3, dist = hit
            if l3.id not in empty_ids:
                skip["nearest_not_empty"] += 1
                continue
            if dist > args.max_km:
                skip["too_far"] += 1
                continue
            if is_same_as_l3(c.name, l3.name):
                skip["same_as_l3"] += 1
                continue
            nn = norm_name(c.name)
            if "thanh pho" in nn or nn.startswith("tinh "):
                skip["city_name"] += 1
                continue
            sid = c.source_id
            if sid in seen_sid or sid in l3.existing_sids:
                skip["dup_sid"] += 1
                continue
            if nn in l3.existing_names:
                skip["dup_name"] += 1
                continue
            seen_sid.add(sid)
            l3.existing_names.add(nn)
            l3.existing_sids.add(sid)
            planned.append(Planned(l3=l3, cand=c, dist_km=dist))
            cover.add(l3.id)

        print(f"to insert: {len(planned)}")
        print(f"cover empty L3: {len(cover)} / {len(empty_ids)}")
        print(f"skip: {dict(skip)}")
        still = sorted(empty_ids - cover)
        if still:
            print("still empty:", [l3_map[i].name for i in still])

        by_l3 = defaultdict(list)
        for p in planned:
            by_l3[p.l3.id].append(p)
        for lid in sorted(by_l3, key=lambda i: -len(by_l3[i]))[: args.sample]:
            ps = by_l3[lid]
            print(f"  · {l3_map[lid].name}: +{len(ps)}  e.g. {ps[0].cand.name} ({ps[0].dist_km:.2f}km [{ps[0].cand.source}])")

        if not args.apply:
            print("DRY-RUN：未写库。加 --apply 写入")
            return 0
        if not planned:
            print("无可写入")
            return 0

        cur.execute("SELECT COALESCE(MAX(id), %s - 1) FROM geo_region WHERE level=4", (ID_FLOOR,))
        next_id = max(int(cur.fetchone()[0]) + 1, ID_FLOOR)

        rows = []
        for p in planned:
            rid = next_id
            next_id += 1
            c = p.cand
            rows.append({
                "id": rid,
                "parent_id": p.l3.id,
                "country_code": COUNTRY,
                "name": c.name,
                "name_en": None,
                "name_ch": None,
                "code": None,
                "level": 4,
                "region_type": (c.kind or "PLACE")[:32],
                "path": build_path(p.l3.path, rid),
                "is_leaf": 1,
                "latitude": c.lat,
                "longitude": c.lng,
                "source": c.source,
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

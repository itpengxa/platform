#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
28_fill_vn_empty_l3_with_streets.py
===================================
空 L3 用街道补 L4（街道也算合法 L4；不映射对方层级）。

匹配（与 L3 挂 L4 相同）:
  父级名 = 本库 L3 → OSM 行政界 name=L3，界内 named highway
  - 库内已有 OSM_STREET：若落在该界内且父级不是该 L3 → 改挂到该 L3
  - 库内没有：写入新 L4（source=OSM_STREET）

用法:
  python3 28_fill_vn_empty_l3_with_streets.py
  python3 28_fill_vn_empty_l3_with_streets.py --apply
"""

from __future__ import annotations

import argparse
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
import osmium.geom
import pymysql
from shapely import wkt as shapely_wkt
from shapely.geometry import Point
from shapely.strtree import STRtree

ROOT = Path(__file__).resolve().parent
PBF = Path(os.environ.get("VN_OSM_PBF", str(ROOT / "raw/osm/vietnam-latest.osm.pbf")))
COUNTRY = "VN"
ID_FLOOR = 4_000_000
BATCH = f"VN_L4_STREET_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

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


@dataclass
class L3:
    id: int
    name: str
    path: str
    lat: Optional[float]
    lng: Optional[float]
    existing_sids: Set[str] = field(default_factory=set)
    existing_names: Set[str] = field(default_factory=set)


@dataclass
class StreetHit:
    source_id: str
    name: str
    lat: Optional[float]
    lng: Optional[float]
    db_id: Optional[int] = None  # 已在库则改挂；否则插入


def main() -> int:
    ap = argparse.ArgumentParser(description="空 L3 用街道补 L4")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--sample", type=int, default=15)
    ap.add_argument("--pbf", default=str(PBF))
    args = ap.parse_args()

    print(f"batch={BATCH} mode={'APPLY' if args.apply else 'DRY-RUN'}")
    pbf = Path(args.pbf)
    if not pbf.is_file():
        print(f"missing PBF: {pbf}")
        return 1

    conn = pymysql.connect(**DB)
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT l3.id, l3.name, l3.path, l3.latitude, l3.longitude
            FROM geo_region l3
            LEFT JOIN (
              SELECT parent_id FROM geo_region
              WHERE country_code=%s AND level=4 AND status=1 GROUP BY parent_id
            ) c ON c.parent_id=l3.id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1
              AND c.parent_id IS NULL
            ORDER BY l3.id
            """,
            (COUNTRY, COUNTRY),
        )
        empty: List[L3] = []
        for r in cur.fetchall():
            empty.append(L3(
                id=int(r[0]), name=r[1] or "", path=r[2] or "",
                lat=float(r[3]) if r[3] is not None else None,
                lng=float(r[4]) if r[4] is not None else None,
            ))
        print(f"empty L3: {len(empty)}")
        if not empty:
            return 0

        empty_by_norm: Dict[str, List[L3]] = defaultdict(list)
        empty_map = {x.id: x for x in empty}
        for x in empty:
            empty_by_norm[norm_name(x.name)].append(x)

        # 库内全部 OSM_STREET（含已挂在别的 L3 下的）
        cur.execute(
            """
            SELECT id, parent_id, name, source_id, latitude, longitude, path
            FROM geo_region
            WHERE country_code=%s AND source='OSM_STREET' AND status=1 AND level=4
            """,
            (COUNTRY,),
        )
        db_streets = []
        sid_to_db = {}
        for r in cur.fetchall():
            sid = r[3] or ""
            row = {
                "id": int(r[0]), "parent_id": int(r[1]), "name": r[2] or "",
                "source_id": sid,
                "lat": float(r[4]) if r[4] is not None else None,
                "lng": float(r[5]) if r[5] is not None else None,
            }
            db_streets.append(row)
            if sid:
                sid_to_db[sid] = row

        # 行政界 name = 空 L3
        wkt_factory = osmium.geom.WKTFactory()
        geoms = []
        geom_l3_ids: List[int] = []
        seen = set()

        class AreaH(osmium.SimpleHandler):
            def area(self, a):
                if a.tags.get("boundary") != "administrative":
                    return
                if (a.tags.get("admin_level") or "") in {"1", "2"}:
                    return
                name = (a.tags.get("name") or a.tags.get("name:vi") or "").strip()
                cands = empty_by_norm.get(norm_name(name))
                if not cands:
                    return
                try:
                    wkt = wkt_factory.create_multipolygon(a)
                    geom = shapely_wkt.loads(wkt) if wkt else None
                except Exception:
                    return
                if not geom or geom.is_empty:
                    return
                # 消歧：取有坐标的、距面心最近的空 L3
                cen = geom.representative_point()
                best = cands[0]
                if len(cands) > 1:
                    best_d = 1e18
                    for c in cands:
                        if c.lat is None:
                            continue
                        d = (c.lat - cen.y) ** 2 + (c.lng - cen.x) ** 2
                        if d < best_d:
                            best_d, best = d, c
                if best.id in seen:
                    idx = geom_l3_ids.index(best.id)
                    if geom.area <= geoms[idx].area:
                        return
                    geoms[idx] = geom
                    return
                seen.add(best.id)
                geoms.append(geom)
                geom_l3_ids.append(best.id)

        print(f"scan admin areas for empty L3 from {pbf} …")
        AreaH().apply_file(str(pbf), locations=True)
        print(f"  polygons: {len(geoms)} / {len(empty)}")
        no_poly = [empty_map[i].name for i in empty_map if i not in seen]
        if no_poly:
            print(f"  no polygon: {no_poly}")

        if not geoms:
            print("无行政界面，无法按父级名匹配")
            return 0

        tree = STRtree(geoms)

        def hit_l3(lat: float, lng: float) -> Optional[L3]:
            pt = Point(lng, lat)
            for i in tree.query(pt):
                g = geoms[int(i)]
                if g.covers(pt) or g.intersects(pt):
                    return empty_map.get(geom_l3_ids[int(i)])
            return None

        # 1) 库内街道：落在空 L3 界内 → 改挂
        reparent: List[Tuple[dict, L3]] = []
        for s in db_streets:
            if s["lat"] is None or s["lng"] is None:
                continue
            l3 = hit_l3(s["lat"], s["lng"])
            if not l3:
                continue
            if s["parent_id"] == l3.id:
                continue
            reparent.append((s, l3))
            if s["source_id"]:
                l3.existing_sids.add(s["source_id"])
            l3.existing_names.add(norm_name(s["name"]))

        print(f"reparent existing streets into empty L3: {len(reparent)}")

        # 2) PBF named highways 在界内且库中没有 → 插入
        inserts: List[Tuple[L3, StreetHit]] = []
        # 先把已在该 L3 下的 sid 记上
        for s in db_streets:
            if s["parent_id"] in empty_map and s["source_id"]:
                empty_map[s["parent_id"]].existing_sids.add(s["source_id"])
                empty_map[s["parent_id"]].existing_names.add(norm_name(s["name"]))

        class WayH(osmium.SimpleHandler):
            def __init__(self):
                super().__init__()
                self.nodes = {}

            def node(self, n):
                # 需要节点坐标算 way 中心 —— 用 locations=True 的 way.nodes
                pass

            def way(self, w):
                if "highway" not in w.tags:
                    return
                name = (w.tags.get("name") or w.tags.get("name:vi") or "").strip()
                if not name:
                    return
                # 需要节点位置：apply_file locations=True 时 w.nodes 有 location
                lats, lngs = [], []
                try:
                    for n in w.nodes:
                        if n.location.valid():
                            lats.append(n.location.lat)
                            lngs.append(n.location.lon)
                except Exception:
                    return
                if not lats:
                    return
                lat, lng = sum(lats) / len(lats), sum(lngs) / len(lngs)
                l3 = hit_l3(lat, lng)
                if not l3:
                    return
                sid = f"way{w.id}"
                if sid in l3.existing_sids or sid in sid_to_db:
                    # 已在库：若在 reparent 列表会处理；已在本 L3 则跳过
                    return
                nn = norm_name(name)
                if nn in l3.existing_names:
                    return
                l3.existing_sids.add(sid)
                l3.existing_names.add(nn)
                inserts.append((l3, StreetHit(sid, name[:256], lat, lng)))

        print("scan named highways …")
        WayH().apply_file(str(pbf), locations=True)
        print(f"new streets to insert: {len(inserts)}")

        cover = set()
        for _, l3 in reparent:
            cover.add(l3.id)
        for l3, _ in inserts:
            cover.add(l3.id)
        print(f"cover empty L3: {len(cover)} / {len(empty)}")
        still = [x.name for x in empty if x.id not in cover]
        if still:
            print(f"still empty after street fill: {still}")

        by = defaultdict(lambda: {"re": 0, "ins": 0})
        for s, l3 in reparent:
            by[l3.name]["re"] += 1
        for l3, h in inserts:
            by[l3.name]["ins"] += 1
        for name, st in sorted(by.items(), key=lambda x: -(x[1]["re"] + x[1]["ins"]))[: args.sample]:
            print(f"  · {name}: reparent={st['re']} insert={st['ins']}")

        if not args.apply:
            print("DRY-RUN：未写库。加 --apply 写入")
            return 0

        conn.begin()
        try:
            # reparent
            for i in range(0, len(reparent), 500):
                for s, l3 in reparent[i:i + 500]:
                    cur.execute(
                        """
                        UPDATE geo_region
                        SET parent_id=%s, path=%s, level=4, updated_at=NOW()
                        WHERE id=%s AND country_code=%s AND source='OSM_STREET'
                        """,
                        (l3.id, build_path(l3.path, s["id"]), s["id"], COUNTRY),
                    )
            # insert
            cur.execute(
                "SELECT COALESCE(MAX(id), %s - 1) FROM geo_region WHERE level=4",
                (ID_FLOOR,),
            )
            next_id = max(int(cur.fetchone()[0]) + 1, ID_FLOOR)
            rows = []
            for l3, h in inserts:
                rid = next_id
                next_id += 1
                rows.append({
                    "id": rid,
                    "parent_id": l3.id,
                    "country_code": COUNTRY,
                    "name": h.name,
                    "name_en": None,
                    "name_ch": None,
                    "code": None,
                    "level": 4,
                    "region_type": "STREET",
                    "path": build_path(l3.path, rid),
                    "is_leaf": 1,
                    "latitude": h.lat,
                    "longitude": h.lng,
                    "source": "OSM_STREET",
                    "source_id": h.source_id,
                    "status": 1,
                    "sort": 0,
                })
            if rows:
                sql = """
                    INSERT INTO geo_region
                      (id, parent_id, country_code, name, name_en, name_ch, code, level, region_type,
                       path, is_leaf, latitude, longitude, source, source_id, status, sort)
                    VALUES
                      (%(id)s,%(parent_id)s,%(country_code)s,%(name)s,%(name_en)s,%(name_ch)s,%(code)s,
                       %(level)s,%(region_type)s,%(path)s,%(is_leaf)s,%(latitude)s,%(longitude)s,
                       %(source)s,%(source_id)s,%(status)s,%(sort)s)
                """
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
            print(f"reparented: {len(reparent)}, inserted: {len(rows)}")
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
        cur.execute(
            """
            SELECT l3.name FROM geo_region l3
            LEFT JOIN (
              SELECT parent_id FROM geo_region
              WHERE country_code=%s AND level=4 AND status=1 GROUP BY parent_id
            ) c ON c.parent_id=l3.id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1 AND c.parent_id IS NULL
            """,
            (COUNTRY, COUNTRY),
        )
        left = [r[0] for r in cur.fetchall()]
        print("still empty:", left)
        print("请清理 Redis: platform:geo:*")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())

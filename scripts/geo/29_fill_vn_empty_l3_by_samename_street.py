#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
29_fill_vn_empty_l3_by_samename_street.py
=========================================
空 L3：用「路名 = L3 核心名」的 OSM highway 补 L4。
路名重复很正常；同一 way 已在库则跳过（不抢挂）。

用法:
  python3 29_fill_vn_empty_l3_by_samename_street.py
  python3 29_fill_vn_empty_l3_by_samename_street.py --apply
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Set

import osmium
import pymysql

ROOT = Path(__file__).resolve().parent
PBF = Path(os.environ.get("VN_OSM_PBF", str(ROOT / "raw/osm/vietnam-latest.osm.pbf")))
COUNTRY = "VN"
ID_FLOOR = 4_000_000
BATCH = f"VN_L4_SAMENAME_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

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


def core_name(l3_name: str) -> str:
    nn = norm_name(l3_name)
    for p in ("xa ", "phuong ", "thi tran ", "thi xa ", "dac khu "):
        if nn.startswith(p):
            return nn[len(p):]
    return nn


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
    core: str


@dataclass
class Hit:
    l3: L3
    source_id: str
    name: str
    lat: float
    lng: float


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--sample", type=int, default=12)
    ap.add_argument("--pbf", default=str(PBF))
    args = ap.parse_args()

    print(f"batch={BATCH} mode={'APPLY' if args.apply else 'DRY-RUN'}")

    conn = pymysql.connect(**DB)
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT l3.id, l3.name, l3.path
            FROM geo_region l3
            LEFT JOIN (
              SELECT parent_id FROM geo_region
              WHERE country_code=%s AND level=4 AND status=1 GROUP BY parent_id
            ) c ON c.parent_id=l3.id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1
              AND c.parent_id IS NULL
            """,
            (COUNTRY, COUNTRY),
        )
        empty = [
            L3(id=int(r[0]), name=r[1] or "", path=r[2] or "", core=core_name(r[1] or ""))
            for r in cur.fetchall()
        ]
        print(f"empty L3: {len(empty)}")
        for e in empty:
            print(f"  · {e.name} core={e.core!r}")
        if not empty:
            return 0

        by_core: Dict[str, List[L3]] = defaultdict(list)
        for e in empty:
            if e.core:
                by_core[e.core].append(e)

        cur.execute(
            """
            SELECT parent_id, source_id FROM geo_region
            WHERE country_code=%s AND source='OSM_STREET' AND source_id IS NOT NULL AND status=1
            """,
            (COUNTRY,),
        )
        # 仅跳过「已挂在该空 L3 下」的 sid；别处同名/同 way 仍可再挂
        existing_under: Dict[int, Set[str]] = defaultdict(set)
        for pid, sid in cur.fetchall():
            existing_under[int(pid)].add(str(sid))
        print(f"OSM_STREET rows indexed under parents: {sum(len(v) for v in existing_under.values())}")

        hits: List[Hit] = []
        seen_pair: Set[str] = set()

        class WayH(osmium.SimpleHandler):
            def way(self, w):
                if "highway" not in w.tags:
                    return
                name = (w.tags.get("name") or w.tags.get("name:vi") or "").strip()
                if not name:
                    return
                nn = norm_name(name)
                matched: List[L3] = []
                if nn in by_core:
                    matched = by_core[nn]
                else:
                    for prefix in ("duong ", "pho ", "hem ", "quoc lo ", "tinh lo "):
                        if nn.startswith(prefix) and nn[len(prefix):] in by_core:
                            matched = by_core[nn[len(prefix):]]
                            break
                if not matched:
                    return
                sid = f"way{w.id}"
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
                for l3 in matched:
                    if sid in existing_under.get(l3.id, ()):
                        continue
                    key = f"{l3.id}:{sid}"
                    if key in seen_pair:
                        continue
                    seen_pair.add(key)
                    hits.append(Hit(l3, sid, name[:256], lat, lng))

        print(f"scan highways from {args.pbf} …")
        WayH().apply_file(args.pbf, locations=True)
        print(f"to insert: {len(hits)}")
        by = defaultdict(int)
        for h in hits:
            by[h.l3.name] += 1
        for name, cnt in sorted(by.items(), key=lambda x: -x[1]):
            print(f"  · {name}: +{cnt}")
        for h in hits[: args.sample]:
            print(f"    {h.name[:28]:<28} → {h.l3.name} {h.source_id} ({h.lat:.4f},{h.lng:.4f})")

        if not args.apply:
            print("DRY-RUN：未写库。加 --apply 写入")
            return 0
        if not hits:
            print("无可写入")
            return 0

        cur.execute("SELECT COALESCE(MAX(id), %s - 1) FROM geo_region WHERE level=4", (ID_FLOOR,))
        next_id = max(int(cur.fetchone()[0]) + 1, ID_FLOOR)
        rows = []
        for h in hits:
            rid = next_id
            next_id += 1
            rows.append({
                "id": rid,
                "parent_id": h.l3.id,
                "country_code": COUNTRY,
                "name": h.name,
                "name_en": None,
                "name_ch": None,
                "code": None,
                "level": 4,
                "region_type": "STREET",
                "path": build_path(h.l3.path, rid),
                "is_leaf": 1,
                "latitude": h.lat,
                "longitude": h.lng,
                "source": "OSM_STREET",
                "source_id": h.source_id,
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
        cur.execute(
            """
            SELECT l3.name FROM geo_region l3
            LEFT JOIN (
              SELECT parent_id FROM geo_region WHERE country_code=%s AND level=4 AND status=1 GROUP BY parent_id
            ) c ON c.parent_id=l3.id
            WHERE l3.country_code=%s AND l3.level=3 AND l3.status=1 AND c.parent_id IS NULL
            """,
            (COUNTRY, COUNTRY),
        )
        print("still empty:", [r[0] for r in cur.fetchall()])
        print("请清理 Redis: platform:geo:*")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())

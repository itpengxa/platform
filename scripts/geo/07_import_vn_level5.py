#!/usr/bin/env python3
"""
GEO-001 Phase 3: Import Vietnam Level 5 (ward/street) from GeoNames PPLX.

PPLX records sit within provinces (admin1). The admin2/3 codes don't match
our geo_region L3/L4 IDs, so we parent them to province (level 2) directly.

Usage:
  python3 07_import_vn_level5.py
"""

import os
import re
import unicodedata
import pymysql
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
COUNTRY_CODE = "VN"
BATCH_ID = f"VN_L5_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", 3306)),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", ""),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}

CHAR_MAP = {'Đ': 'D', 'đ': 'd'}


def strip_diacritics(s):
    if not s:
        return ""
    s = ''.join(CHAR_MAP.get(ch, ch) for ch in s)
    nfkd = unicodedata.normalize("NFKD", s)
    return re.sub(r"[^a-zA-Z0-9 ,\-]", "", nfkd).strip().lower()


def clean_name(name):
    name = re.sub(r"\s*\(.*?\)$", "", name).strip()
    name = re.sub(r" (Province|City|Municipality)$", "", name).strip()
    return name


def load_admin1_to_province(conn):
    """Load existing provinces, return {admin1_code: province_id}."""
    cur = conn.cursor()
    cur.execute("""
        SELECT id, name, name_en, source_id
        FROM geo_region
        WHERE country_code = 'VN' AND level = 2
    """)
    provinces = {}
    for row in cur.fetchall():
        pid, name, name_en, sid = row
        provinces[pid] = {
            "name": name,
            "name_en": name_en or name,
            "norm": strip_diacritics(name),
            "norm_en": strip_diacritics(name_en or ""),
            "source_id": sid,
        }
    cur.close()

    # Build admin1 -> province mapping using source_id (admin1 code for GEONAMES provinces)
    # For non-GEONAMES provinces, try name matching
    admin1_map = {}
    # Load admin1Codes.txt
    path = RAW / "admin1Codes.txt"
    if not path.exists():
        path = Path("/tmp/admin1Codes.txt")
    if path.exists():
        with path.open(encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3 or not parts[0].startswith("VN."):
                    continue
                a1 = parts[0][3:]
                gn_name = clean_name(parts[1])
                gn_norm = strip_diacritics(gn_name)
                # Match by name
                for pid, p in provinces.items():
                    if gn_norm == p["norm"] or gn_norm == p["norm_en"]:
                        admin1_map[a1] = pid
                        break

    print(f"  admin1->province mapping: {len(admin1_map)} codes")
    return admin1_map


def main():
    print(f"{'='*60}")
    print(f"Phase 3: Import VN Level 5 (PPLX) data")
    print(f"Batch: {BATCH_ID}\n")

    # 1. Parse PPLX
    print("[1/4] Parsing PPLX records...")
    pplx = []
    with open(RAW / "VN.txt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 12 or parts[7] != "PPLX":
                continue
            pplx.append({
                "geonameid": parts[0],
                "name": parts[1],
                "asciiname": parts[2],
                "latitude": float(parts[4]) if parts[4] else None,
                "longitude": float(parts[5]) if parts[5] else None,
                "admin1": parts[10],
            })
    print(f"  Found {len(pplx)} PPLX records")

    # 2. Build admin1->province mapping
    print("[2/4] Building province mapping...")
    conn = pymysql.connect(**DB_CONFIG)
    admin1_map = load_admin1_to_province(conn)
    conn.close()

    # 3. Group PPLX by province
    print("[3/4] Grouping by province...")
    by_province = defaultdict(list)
    unmatched = []
    for r in pplx:
        pid = admin1_map.get(r["admin1"])
        if pid:
            by_province[pid].append(r)
        else:
            unmatched.append(r)

    total_matched = sum(len(v) for v in by_province.values())
    print(f"  Matched to province: {total_matched}")
    if unmatched:
        print(f"  Unmatched (no province): {len(unmatched)}")

    # 4. Load into MySQL
    print("[4/4] Loading into MySQL...")
    INSERT_SQL = """INSERT INTO geo_region
(id, parent_id, country_code, name, name_en, name_ch, code,
 level, region_type, path, is_leaf,
 latitude, longitude, source, source_id, status, sort,
 created_at, updated_at)
VALUES (%s, %s, %s, %s, %s, %s, %s,
        %s, %s, %s, %s,
        %s, %s, %s, %s, %s, %s,
        NOW(), NOW())
ON DUPLICATE KEY UPDATE
 name=VALUES(name), name_en=VALUES(name_en),
 latitude=VALUES(latitude), longitude=VALUES(longitude),
 updated_at=NOW()"""

    conn = pymysql.connect(**DB_CONFIG)
    conn.autocommit(False)
    cur = conn.cursor()

    # Get province paths
    cur.execute("SELECT id, path FROM geo_region WHERE country_code='VN' AND level=2")
    province_paths = dict(cur.fetchall())

    try:
        loaded = 0
        for pid, records in by_province.items():
            p_path = province_paths.get(pid, f"/240/{pid}/")
            for r in records:
                rid = int(r["geonameid"])
                cur.execute(INSERT_SQL, (
                    rid, pid, COUNTRY_CODE,
                    r["name"], r["asciiname"], None, None,
                    5, "WARD", f"{p_path}{rid}/", 1,
                    r["latitude"], r["longitude"],
                    "GEONAMES", r["geonameid"],
                    1, 0,
                ))
                loaded += 1

        conn.commit()

        # Rebuild is_leaf for affected provinces
        cur.execute("""
            UPDATE geo_region r
            LEFT JOIN (SELECT parent_id, COUNT(*) cnt FROM geo_region
                       WHERE country_code='VN' AND level=5 GROUP BY parent_id) c
              ON r.id = c.parent_id
            SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
            WHERE r.country_code='VN' AND r.level = 2
        """)
        conn.commit()

        print(f"\n{'='*60}")
        print(f"✅ Phase 3 Complete: {loaded} level-5 records loaded")
        print(f"{'='*60}")

        cur.execute("""
            SELECT level, region_type, COUNT(*)
            FROM geo_region WHERE country_code='VN'
            GROUP BY level, region_type ORDER BY level
        """)
        for row in cur.fetchall():
            print(f"  Level {row[0]} ({row[1]:15s}): {row[2]:,}")

        # Show sample
        cur.execute("""
            SELECT p.name_en AS province, w.name AS ward
            FROM geo_region w
            JOIN geo_region p ON p.id = w.parent_id
            WHERE w.country_code='VN' AND w.level=5
            LIMIT 3
        """)
        print("\nSample wards:")
        for row in cur.fetchall():
            print(f"  {row[0]} -> {row[1]}")

    except Exception as e:
        conn.rollback()
        print(f"Error: {e}")
        raise
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()

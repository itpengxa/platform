#!/usr/bin/env python3
"""
GEO-001 Phase 2: Import Vietnam Level 4 (district/ward) from GeoNames ADM3.

Strategy:
  1. Parse GeoNames VN.txt for ADM3 records (districts/wards = level 4)
  2. Map admin1 code -> province name via admin1Codes.txt
  3. Match province name to existing geo_region.level2 (diacritic-insensitive)
  4. For unmapped provinces -> create them as level 2
  5. Load level 4 into geo_region, parented to province
  6. Rebuild is_leaf

Usage:
  python3 06_import_vn_level4.py
"""

import json
import os
import re
import unicodedata
import urllib.request
import pymysql
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
PATH_COUNTRY_ID = 240
COUNTRY_CODE = "VN"
BATCH_ID = f"VN_L4_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", 3306)),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", ""),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}


def strip_diacritics(s):
    """Remove Vietnamese/unicode diacritics for fuzzy matching.
    Handles special chars like Đ/đ -> d, Vietnamese tones, etc.
    
    Examples:
      'Cà Mau' -> 'ca mau', 'Hà Nội' -> 'ha noi'
      'Đồng Nai' -> 'dong nai', 'Đắk Lắk' -> 'dak lak'
    """
    if not s:
        return ""
    # Vietnamese character mapping (Unicode -> ASCII)
    CHAR_MAP = {
        'Đ': 'D', 'đ': 'd',
    }
    result = []
    for ch in s:
        if ch in CHAR_MAP:
            result.append(CHAR_MAP[ch])
        else:
            result.append(ch)
    s = ''.join(result)
    # NFKD decomposition for remaining diacritics
    nfkd = unicodedata.normalize("NFKD", s)
    # Keep only ASCII letters, digits, commas, spaces, hyphens
    ascii_str = re.sub(r"[^a-zA-Z0-9 ,\-]", "", nfkd)
    return ascii_str.strip().lower()


def clean_province_name(name):
    """Remove suffixes like ' Province', ' City', ' Municipality', '(HCMC)'.
    Order: remove parenthetical first, then trailing suffixes."""
    name = re.sub(r"\s*\(.*?\)$", "", name).strip()
    name = re.sub(r" (Province|City|Municipality)$", "", name).strip()
    return name


def load_admin1_map():
    """Load GeoNames admin1Codes.txt -> {admin1_code: (name, ascii_name)}"""
    path = RAW / "admin1Codes.txt"
    if not path.exists():
        path = Path("/tmp/admin1Codes.txt")
    if not path.exists():
        print("Downloading admin1Codes.txt...")
        url = "https://download.geonames.org/export/dump/admin1CodesASCII.txt"
        urllib.request.urlretrieve(url, "/tmp/admin1Codes.txt")
        path = Path("/tmp/admin1Codes.txt")

    mapping = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            key = parts[0]
            if not key.startswith(f"{COUNTRY_CODE}."):
                continue
            mapping[key[3:]] = (parts[1], parts[2])
    print(f"  Loaded {len(mapping)} admin1 mappings for {COUNTRY_CODE}")
    return mapping


def parse_adm3(filepath):
    """Parse GeoNames VN.txt, extract ADM3 records."""
    records = []
    with open(filepath, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 12 or parts[7] != "ADM3":
                continue
            records.append({
                "geonameid": parts[0],
                "name": parts[1],
                "asciiname": parts[2],
                "latitude": float(parts[4]) if parts[4] else None,
                "longitude": float(parts[5]) if parts[5] else None,
                "admin1": parts[10],
            })
    return records


def fuzzy_match(name, ascii_name, existing):
    """Match a GeoNames province name to existing provinces (diacritic-insensitive)."""
    # Clean the name first (remove suffixes like " City", " Province", "(HCMC)")
    name_clean_gn = clean_province_name(name)
    ascii_clean_gn = clean_province_name(ascii_name)
    norm = strip_diacritics(name_clean_gn)
    norm_ascii = strip_diacritics(ascii_clean_gn)
    
    for pid, p in existing.items():
        if norm == p["norm"] or norm == p["norm_en"] or \
           norm_ascii == p["norm"] or norm_ascii == p["norm_en"]:
            return pid
    
    # Space-normalized (GeoNames uses no spaces: "Hanoi" vs "Ha Noi")
    norm_ns = norm.replace(" ", "").replace("-", "")
    for pid, p in existing.items():
        p_ns = p["norm"].replace(" ", "").replace("-", "")
        if norm_ns == p_ns or norm_ns == p["norm_en"].replace(" ", "").replace("-", ""):
            return pid
    
    # Hyphen-normalized (GeoNames "Thua Thien Hue" vs DB "thua thien-hue")
    norm_hyphen = norm.replace("-", " ")
    for pid, p in existing.items():
        if norm_hyphen == p["norm"]:
            return pid
    
    return None


def main():
    print(f"{'='*60}")
    print(f"Phase 2: Import VN Level 4 data")
    print(f"Batch: {BATCH_ID}\n")

    # 1. Load admin1 mapping
    print("[1/6] Loading admin1->province mapping...")
    admin1_map = load_admin1_map()

    # 2. Parse ADM3
    print("[2/6] Parsing ADM3 records...")
    adm3 = parse_adm3(RAW / "VN.txt")
    print(f"  Found {len(adm3)} ADM3 records")

    # 3. Load existing provinces from DB
    print("[3/6] Loading existing provinces...")
    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()
    cur.execute("""
        SELECT id, name, name_en FROM geo_region
        WHERE country_code = 'VN' AND level = 2
    """)
    existing = {}
    all_ids = set()
    for row in cur.fetchall():
        pid, name, name_en = row
        existing[pid] = {
            "id": pid,
            "name": name,
            "name_en": name_en or name,
            "norm": strip_diacritics(name),
            "norm_en": strip_diacritics(name_en or ""),
        }
        all_ids.add(pid)
    # Add all region IDs to avoid conflicts
    cur.execute("SELECT id FROM geo_region WHERE country_code = 'VN'")
    for row in cur.fetchall():
        all_ids.add(row[0])
    conn.close()
    print(f"  Existing: {len(existing)} provinces")

    # 4. Group ADM3 by admin1, match to province
    print("[4/6] Matching ADM3 to provinces...")
    by_admin1 = defaultdict(list)
    for r in adm3:
        by_admin1[r["admin1"]].append(r)

    province_adm3 = {}  # {province_id: [adm3_records]}
    new_provinces = {}  # {temp_id: info_dict}
    matched_admin1 = set()
    unmatched = []

    for a1, records in sorted(by_admin1.items()):
        info = admin1_map.get(a1)
        if not info:
            unmatched.append((a1, len(records)))
            continue
        name, ascii_name = info
        mid = fuzzy_match(name, ascii_name, existing)
        if mid:
            province_adm3.setdefault(mid, []).extend(records)
            matched_admin1.add(a1)
        else:
            # Create new province
            base = 100000 + (int(a1) if a1.isdigit() else 200000)
            nid = base
            while nid in all_ids:
                nid += 1
            all_ids.add(nid)
            new_provinces[nid] = {
                "admin1_code": a1,
                "name": clean_province_name(name),
                "name_en": clean_province_name(ascii_name),
                "adm3_records": records,
            }

    print(f"  Matched to existing: {len(matched_admin1)} provinces, "
          f"{sum(len(v) for v in province_adm3.values()):,} records")
    print(f"  New provinces needed: {len(new_provinces)} provinces, "
          f"{sum(v['adm3_records'].__len__() for v in new_provinces.values()):,} records")
    if unmatched:
        print(f"  UNMATCHED admin1 codes: {len(unmatched)}")
        for a1, cnt in unmatched[:5]:
            print(f"    admin1={a1}: {cnt} records")

    # 5. Insert into MySQL
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

    print("[5/6] Inserting into MySQL...")
    conn = pymysql.connect(**DB_CONFIG)
    conn.autocommit(False)
    cur = conn.cursor()

    try:
        # 5a. Insert new provinces (level 2)
        new_cnt = 0
        for nid, info in new_provinces.items():
            p_path = f"/{PATH_COUNTRY_ID}/{nid}/"
            cur.execute(INSERT_SQL, (
                nid, PATH_COUNTRY_ID, COUNTRY_CODE,
                info["name"], info["name_en"], None, None,
                2, "PROVINCE", p_path, 0,
                None, None, "GEONAMES", info["admin1_code"],
                1, 0,
            ))
            new_cnt += 1
            # Add to existing for ADM3 parenting
            existing[nid] = {
                "id": nid,
                "name": info["name"],
                "name_en": info["name_en"],
                "norm": strip_diacritics(info["name"]),
                "norm_en": strip_diacritics(info["name_en"]),
            }
        conn.commit()
        print(f"  Created {new_cnt} new provinces")

        # 5b. Insert ADM3 as level 4
        l4_cnt = 0
        for pid, records in province_adm3.items():
            p_path = existing[pid].get("path", f"/{PATH_COUNTRY_ID}/{pid}/")
            for rec in records:
                rid = int(rec["geonameid"])
                cur.execute(INSERT_SQL, (
                    rid, pid, COUNTRY_CODE,
                    rec["name"], rec["asciiname"], None, None,
                    4, "DISTRICT", f"{p_path}{rid}/", 1,
                    rec["latitude"], rec["longitude"],
                    "GEONAMES", rec["geonameid"],
                    1, 0,
                ))
                l4_cnt += 1
                if l4_cnt % 500 == 0:
                    conn.commit()

        # Also insert new province ADM3 records
        for nid, info in new_provinces.items():
            p_path = f"/{PATH_COUNTRY_ID}/{nid}/"
            for rec in info["adm3_records"]:
                rid = int(rec["geonameid"])
                cur.execute(INSERT_SQL, (
                    rid, nid, COUNTRY_CODE,
                    rec["name"], rec["asciiname"], None, None,
                    4, "DISTRICT", f"{p_path}{rid}/", 1,
                    rec["latitude"], rec["longitude"],
                    "GEONAMES", rec["geonameid"],
                    1, 0,
                ))
                l4_cnt += 1
                if l4_cnt % 500 == 0:
                    conn.commit()

        conn.commit()
        print(f"  Loaded {l4_cnt:,} level-4 records")

        # 6. Rebuild is_leaf
        print("[6/6] Rebuilding is_leaf...")
        cur.execute("""
            UPDATE geo_region r
            LEFT JOIN (SELECT parent_id, COUNT(*) cnt FROM geo_region
                       WHERE country_code='VN' AND level>1 GROUP BY parent_id) c
              ON r.id = c.parent_id
            SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
            WHERE r.country_code='VN' AND r.level IN (1,2,3)
        """)
        conn.commit()

        # Update max_level
        cur.execute("UPDATE geo_country SET max_level=5 WHERE iso2='VN'")
        conn.commit()

        # Verify
        print(f"\n{'='*60}")
        print("✅ Complete")
        print(f"{'='*60}")
        cur.execute("""
            SELECT level, region_type, COUNT(*)
            FROM geo_region WHERE country_code='VN'
            GROUP BY level, region_type ORDER BY level
        """)
        for r in cur.fetchall():
            print(f"  Level {r[0]} ({r[1]:15s}): {r[2]:,}")

        cur.execute("""
            SELECT COUNT(*) FROM geo_region
            WHERE country_code='VN' AND parent_id!=0
              AND parent_id NOT IN (SELECT id FROM geo_region)
        """)
        print(f"  Orphans: {cur.fetchone()[0]}")

        # Double-check province names
        cur.execute("""
            SELECT source, COUNT(*) FROM geo_region
            WHERE country_code='VN' AND level=2 GROUP BY source
        """)
        for r in cur.fetchall():
            print(f"  Provinces ({r[0]}): {r[1]}")

    except Exception as e:
        conn.rollback()
        print(f"Error: {e}")
        raise
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()

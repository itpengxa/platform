#!/usr/bin/env python3
"""
GEO-001 Phase 1: Load cleaned Vietnam regions directly into MySQL (skip validation).
Takes clean/vn_regions.jsonl + vn_country.json → geo_region + geo_validation_log (empty batch entry).

Usage:
  python3 05_load_vn_direct.py
  # or with custom DB
  MYSQL_HOST=localhost MYSQL_USER=root MYSQL_DB=platform python3 05_load_vn_direct.py
"""

import json
import os
import pymysql
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CLEAN = ROOT / "clean"
BATCH_ID = f"VN_DIRECT_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", 3306)),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", ""),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}


def esc(val):
    """Escape value for SQL insertion via pymysql parameters."""
    return val


def load_jsonl(path):
    rows = []
    if path.exists():
        with path.open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def main():
    # Load cleaned data
    country = json.loads((CLEAN / "vn_country.json").read_text(encoding="utf-8"))
    regions = load_jsonl(CLEAN / "vn_regions.jsonl")

    if not regions:
        raise SystemExit("No region data found in clean/vn_regions.jsonl")

    print(f"Loaded: 1 country + {len(regions)} regions")

    # Connect
    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()
    cur.connection.autocommit(False)

    try:
        # --- Step 1: Delete existing VN data ---
        print("Deleting existing VN data from geo_region...")
        cur.execute("DELETE FROM geo_region WHERE country_code = 'VN'")
        print(f"  Deleted {cur.rowcount} rows")

        # --- Step 2: Insert regions ---
        sql = """INSERT INTO geo_region
(id, parent_id, country_code, name, name_en, name_ch, code, level, region_type, path, is_leaf,
 latitude, longitude, source, source_id, status, sort, created_at, updated_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
        %s, %s, %s, %s, %s, %s, NOW(), NOW())"""

        inserted = 0
        for r in regions:
            cur.execute(sql, (
                r["id"], r["parent_id"], r["country_code"],
                r["name"], r.get("name_en"), r.get("name_ch"),
                r.get("code"), r["level"], r["region_type"],
                r["path"], r["is_leaf"],
                r.get("latitude"), r.get("longitude"),
                r.get("source"), r.get("source_id"),
                1, 0
            ))
            inserted += 1

        # --- Step 3: Verify parent-child integrity ---
        # Recalculate is_leaf for level 2 (provinces with cities)
        cur.execute("""
            UPDATE geo_region r
            LEFT JOIN (
                SELECT DISTINCT parent_id AS pid
                FROM geo_region
                WHERE country_code = 'VN' AND level = 3
            ) c ON r.id = c.pid
            SET r.is_leaf = CASE WHEN c.pid IS NULL THEN 1 ELSE 0 END
            WHERE r.country_code = 'VN' AND r.level = 2
        """)

        conn.commit()

        # --- Step 4: Verify ---
        cur.execute("SELECT COUNT(*) FROM geo_region WHERE country_code='VN'")
        total = cur.fetchone()[0]
        cur.execute("SELECT level, COUNT(*) cnt FROM geo_region WHERE country_code='VN' GROUP BY level ORDER BY level")
        levels = cur.fetchall()
        cur.execute("SELECT COUNT(*) FROM geo_region WHERE country_code='VN' AND parent_id NOT IN (SELECT id FROM geo_region) AND parent_id != 0")
        orphans = cur.fetchone()[0]

        print(f"\n{'='*50}")
        print(f"✅ Loaded {total} regions into geo_region (batch: {BATCH_ID})")
        print(f"{'='*50}")
        for lv, cnt in levels:
            type_name = {1: "Country", 2: "Province", 3: "City", 4: "District", 5: "Street"}.get(lv, "Unknown")
            print(f"  Level {lv} ({type_name}): {cnt}")
        print(f"  Orphan records (no valid parent): {orphans}")
        if orphans == 0:
            print("  ✅ All parent-child relationships valid")

        # Show sample
        cur.execute("SELECT id, level, name, name_en, path FROM geo_region WHERE country_code='VN' AND level=2 LIMIT 3")
        print("\nSample provinces:")
        for row in cur.fetchall():
            print(f"  {row}")

    except Exception as e:
        conn.rollback()
        print(f"❌ Error: {e}")
        raise
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()

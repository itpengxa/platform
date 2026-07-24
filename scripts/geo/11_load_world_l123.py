#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
11_load_world_l123.py
=====================
导入全球 CSC 1~3 级。区划 id 按层级号段（与 18_remap_level_ids 一致）：

  L1 country.id              保持原样（1~250）
  L2 state   → 200_000_000 + 顺序号（导入时按 MAX(level=2)+1）
  L3 city    → 300_000_000 + 顺序号

历史曾用 1e9/2e9 + CSC id；现库请以号段为准，必要时跑 18_remap_level_ids.py。

会：
  1) 删除全部 level<=3
  2) 重写 L1~L3
  3) 把已有 L4/L5 的 parent_id / path 从「旧 CSC id」迁移到新 id（保 VN）

用法:
  python3 11_load_world_l123.py --apply
  python3 11_load_world_l123.py --apply --countries CN,US,TH
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import pymysql

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
BATCH = f"WORLD_L123_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

STATE_BASE = 200_000_000
CITY_BASE = 300_000_000

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


def fnum(v) -> Optional[float]:
    if v is None or v == "":
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def zh(translations) -> Optional[str]:
    if not isinstance(translations, dict):
        return None
    return translations.get("zh-CN") or translations.get("zh") or translations.get("cn")


def phone_code(raw) -> Optional[str]:
    if raw is None or raw == "":
        return None
    s = str(raw).strip()
    if not s:
        return None
    return s if s.startswith("+") else f"+{s}"


def region_type_state(stype: Optional[str]) -> str:
    t = (stype or "state").lower()
    if "prov" in t:
        return "PROVINCE"
    return "STATE"


def sid(state_csc_id: int) -> int:
    return STATE_BASE + state_csc_id


def cid(city_csc_id: int) -> int:
    return CITY_BASE + city_csc_id


def load_json(path: Path):
    print(f"  loading {path.name} ...", flush=True)
    data = json.loads(path.read_text(encoding="utf-8"))
    print(f"  → {len(data):,} rows", flush=True)
    return data


def build_rows(countries, states, cities, only: Optional[Set[str]]):
    country_by_iso = {}
    for c in countries:
        iso2 = (c.get("iso2") or "").upper()
        if not iso2:
            continue
        if only and iso2 not in only:
            continue
        country_by_iso[iso2] = c

    country_rows, region_rows = [], []
    stats = {"countries": 0, "states": 0, "cities": 0, "cities_orphan_state": 0}

    for iso2, c in sorted(country_by_iso.items()):
        country_id = int(c["id"])
        name_local = c.get("native") or c.get("name")
        name_en = c.get("name")
        name_ch = zh(c.get("translations"))
        country_rows.append({
            "id": country_id,
            "iso2": iso2,
            "iso3": (c.get("iso3") or "")[:3],
            "name": name_local,
            "name_en": name_en,
            "name_ch": name_ch,
            "phone_code": phone_code(c.get("phonecode")),
            "currency_code": c.get("currency"),
            "max_level": 3,
        })
        region_rows.append({
            "id": country_id,
            "parent_id": 0,
            "country_code": iso2,
            "name": name_local,
            "name_en": name_en,
            "name_ch": name_ch,
            "code": iso2,
            "level": 1,
            "region_type": "COUNTRY",
            "path": f"/{country_id}/",
            "is_leaf": 0,
            "latitude": fnum(c.get("latitude")),
            "longitude": fnum(c.get("longitude")),
            "source": "CSC",
            "source_id": str(country_id),
            "sort": 0,
        })
        stats["countries"] += 1

    state_ok: Set[int] = set()  # new state ids
    old_state_to_new: Dict[int, int] = {}
    state_country: Dict[int, str] = {}
    for i, s in enumerate(states):
        iso2 = (s.get("country_code") or "").upper()
        if iso2 not in country_by_iso:
            continue
        country_id = int(s.get("country_id") or country_by_iso[iso2]["id"])
        old = int(s["id"])
        new = sid(old)
        old_state_to_new[old] = new
        state_country[old] = iso2
        name_local = s.get("native") or s.get("name")
        name_en = s.get("name")
        region_rows.append({
            "id": new,
            "parent_id": country_id,
            "country_code": iso2,
            "name": name_local,
            "name_en": name_en,
            "name_ch": zh(s.get("translations")),
            "code": s.get("iso3166_2") or s.get("iso2"),
            "level": 2,
            "region_type": region_type_state(s.get("type")),
            "path": f"/{country_id}/{new}/",
            "is_leaf": 0,
            "latitude": fnum(s.get("latitude")),
            "longitude": fnum(s.get("longitude")),
            "source": "CSC",
            "source_id": str(old),
            "sort": i,
        })
        state_ok.add(new)
        stats["states"] += 1

    old_city_to_new: Dict[int, int] = {}
    city_country: Dict[int, str] = {}
    for i, city in enumerate(cities):
        iso2 = (city.get("country_code") or "").upper()
        if iso2 not in country_by_iso:
            continue
        country_id = int(city.get("country_id") or country_by_iso[iso2]["id"])
        old = int(city["id"])
        new = cid(old)
        old_city_to_new[old] = new
        city_country[old] = iso2
        old_state = int(city.get("state_id") or 0)
        new_state = old_state_to_new.get(old_state)
        name_local = city.get("native") or city.get("name")
        name_en = city.get("name")
        if new_state and new_state in state_ok:
            parent_id = new_state
            path = f"/{country_id}/{new_state}/{new}/"
        else:
            parent_id = country_id
            path = f"/{country_id}/{new}/"
            stats["cities_orphan_state"] += 1
        region_rows.append({
            "id": new,
            "parent_id": parent_id,
            "country_code": iso2,
            "name": name_local,
            "name_en": name_en,
            "name_ch": zh(city.get("translations")),
            "code": None,
            "level": 3,
            "region_type": "CITY",
            "path": path,
            "is_leaf": 1,
            "latitude": fnum(city.get("latitude")),
            "longitude": fnum(city.get("longitude")),
            "source": "CSC",
            "source_id": str(old),
            "sort": i,
        })
        stats["cities"] += 1

    return country_rows, region_rows, stats, old_state_to_new, old_city_to_new, state_country, city_country


def upsert_countries(cur, rows: List[dict], chunk: int = 200) -> None:
    sql = """
    INSERT INTO geo_country
      (id, iso2, iso3, name, name_en, name_ch, phone_code, currency_code, max_level, status, sort, created_at, updated_at)
    VALUES
      (%(id)s, %(iso2)s, %(iso3)s, %(name)s, %(name_en)s, %(name_ch)s, %(phone_code)s, %(currency_code)s, %(max_level)s, 1, 0, NOW(), NOW())
    ON DUPLICATE KEY UPDATE
      iso3=VALUES(iso3), name=VALUES(name), name_en=VALUES(name_en), name_ch=VALUES(name_ch),
      phone_code=VALUES(phone_code), currency_code=VALUES(currency_code),
      max_level=IF(max_level > VALUES(max_level), max_level, VALUES(max_level)),
      status=1, updated_at=NOW()
    """
    for i in range(0, len(rows), chunk):
        cur.executemany(sql, rows[i:i + chunk])


def insert_regions(cur, rows: List[dict], chunk: int = 800) -> None:
    sql = """
    INSERT INTO geo_region
      (id, parent_id, country_code, name, name_en, name_ch, code, level, region_type, path, is_leaf,
       latitude, longitude, source, source_id, status, sort, created_at, updated_at)
    VALUES
      (%(id)s, %(parent_id)s, %(country_code)s, %(name)s, %(name_en)s, %(name_ch)s, %(code)s, %(level)s, %(region_type)s, %(path)s, %(is_leaf)s,
       %(latitude)s, %(longitude)s, %(source)s, %(source_id)s, 1, %(sort)s, NOW(), NOW())
    """
    for i in range(0, len(rows), chunk):
        cur.executemany(sql, rows[i:i + chunk])
        if (i // chunk) % 25 == 0:
            print(f"    regions insert {min(i + chunk, len(rows)):,}/{len(rows):,}", flush=True)


def migrate_l45_parents(cur, old_state_to_new: Dict[int, int], old_city_to_new: Dict[int, int],
                        state_country: Dict[int, str], city_country: Dict[int, str]) -> Tuple[int, int]:
    """按「同国家」优先迁移 parent，避免 CSC state/city 数字 id 撞车。"""
    cur.execute("SELECT id, parent_id, country_code, level FROM geo_region WHERE level >= 4 AND status=1")
    rows = list(cur.fetchall())
    if not rows:
        return 0, 0

    parent_updates = []
    for nid, parent_id, cc, level in rows:
        nid, parent_id = int(nid), int(parent_id)
        cc = (cc or "").upper()
        new_parent = parent_id
        # 同国家的省优先于同号市
        if parent_id in old_state_to_new and state_country.get(parent_id) == cc:
            new_parent = old_state_to_new[parent_id]
        elif parent_id in old_city_to_new and city_country.get(parent_id) == cc:
            new_parent = old_city_to_new[parent_id]
        elif parent_id in old_city_to_new:
            new_parent = old_city_to_new[parent_id]
        elif parent_id in old_state_to_new:
            new_parent = old_state_to_new[parent_id]
        parent_updates.append((new_parent, nid))

    cur.executemany("UPDATE geo_region SET parent_id=%s WHERE id=%s", parent_updates)

    cur.execute("SELECT id, path FROM geo_region WHERE status=1")
    path_by_id = {int(r[0]): r[1] for r in cur.fetchall()}
    cur.execute("SELECT id, parent_id, level FROM geo_region WHERE level >= 4 AND status=1 ORDER BY level ASC, id ASC")
    path_updates = []
    for nid, parent_id, level in cur.fetchall():
        nid, parent_id = int(nid), int(parent_id)
        parent_path = path_by_id.get(parent_id)
        if not parent_path:
            continue
        new_path = parent_path if parent_path.endswith("/") else parent_path + "/"
        new_path = f"{new_path}{nid}/"
        path_updates.append((new_path, nid))
        path_by_id[nid] = new_path
    if path_updates:
        cur.executemany("UPDATE geo_region SET path=%s, updated_at=NOW() WHERE id=%s", path_updates)
    return len(parent_updates), len(path_updates)


def rebuild_is_leaf(cur) -> None:
    cur.execute(
        """
        UPDATE geo_region r
        LEFT JOIN (
            SELECT parent_id, COUNT(*) cnt FROM geo_region WHERE status=1 GROUP BY parent_id
        ) c ON r.id = c.parent_id
        SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
        """
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--countries", default="")
    args = ap.parse_args()
    only = {x.strip().upper() for x in args.countries.split(",") if x.strip()} or None

    hr("WORLD L1-L3 Import (ID namespaced)", "=")
    print(f"  batch: {BATCH}")
    print(f"  mode : {'APPLY' if args.apply else 'DRY-RUN'}")
    print(f"  STATE_BASE={STATE_BASE} CITY_BASE={CITY_BASE}")

    for req in ("countries.json", "states.json", "cities.json"):
        if not (RAW / req).exists():
            print(f"缺少 {RAW/req}，先跑 bash 10_download_world_l123.sh")
            return 1

    countries = load_json(RAW / "countries.json")
    states = load_json(RAW / "states.json")
    cities = load_json(RAW / "cities.json")

    country_rows, region_rows, stats, old_state_to_new, old_city_to_new, state_country, city_country = build_rows(
        countries, states, cities, only
    )
    hr("构建摘要")
    print(f"  countries={stats['countries']:,} states={stats['states']:,} cities={stats['cities']:,}")
    print(f"  region rows={len(region_rows):,} orphan_city={stats['cities_orphan_state']:,}")
    print(f"  sample state id 3807 → {sid(3807)}")
    print(f"  sample city  id 130606 → {cid(130606)}")

    if not args.apply:
        print("\nDRY-RUN 完成。加 --apply 写库。")
        return 0

    conn = pymysql.connect(**DB_CONFIG)
    try:
        cur = conn.cursor()
        conn.begin()
        hr("1) 删除旧 L1~L3")
        cur.execute("DELETE FROM geo_region WHERE level <= 3")
        print(f"  deleted {cur.rowcount:,}")

        hr("2) upsert geo_country")
        upsert_countries(cur, country_rows)

        hr("3) insert L1~L3")
        insert_regions(cur, region_rows)

        hr("4) 迁移已有 L4/L5 parent+path")
        pu, pathu = migrate_l45_parents(
            cur, old_state_to_new, old_city_to_new, state_country, city_country
        )
        print(f"  parent updates={pu:,} path updates={pathu:,}")

        hr("5) is_leaf + VN max_level")
        rebuild_is_leaf(cur)
        cur.execute("UPDATE geo_country SET max_level=5 WHERE iso2='VN'")
        conn.commit()

        cur.execute("SELECT level, COUNT(*) FROM geo_region GROUP BY level ORDER BY level")
        print("  levels:", dict(cur.fetchall()))
        cur.execute("SELECT COUNT(*) FROM geo_region WHERE level=1")
        print(f"  L1 countries: {cur.fetchone()[0]}")
        cur.execute("SELECT id,name,level FROM geo_region WHERE id IN (%s,%s,%s)",
                    (240, sid(3807), cid(130606)))
        print("  VN check:", cur.fetchall())
        cur.execute("""
            SELECT child.level, parent.level, COUNT(*)
            FROM geo_region child
            JOIN geo_region parent ON parent.id=child.parent_id
            WHERE child.country_code='VN' AND child.level>=2
            GROUP BY child.level, parent.level ORDER BY 1,2
        """)
        print("  VN edges:", cur.fetchall())
        return 0
    except Exception as e:
        conn.rollback()
        print("失败回滚:", e)
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())

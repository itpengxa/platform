#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
15_import_l5_match_l4.py
========================
导入 5 级（GeoNames ADM4）：
  源：父名（ADM3）、name/name_en、source_id、经纬度
  库：parent_id=匹配到的 L4.id；country_code/level/path 用我们的；id 自增

跳过 CN。VN 已有部分 L5，靠 source_id 去重。

用法:
  python3 15_import_l5_match_l4.py --file /path/allCountries.txt --limit 1 --apply
  python3 15_import_l5_match_l4.py --file /path/allCountries.txt --apply
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import unicodedata
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import pymysql

ROOT = Path(__file__).resolve().parent
GN = ROOT / "raw" / "geonames"
BATCH = f"L5_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}

SKIP_COUNTRIES = {"CN"}
CHAR_MAP = {"Đ": "D", "đ": "d"}


def hr(title: str = "", char: str = "─", width: int = 64) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print(char * left + f" {title} " + char * (pad - left))
    else:
        print(char * width)


def norm_name(s: Optional[str]) -> str:
    if not s:
        return ""
    s = "".join(CHAR_MAP.get(ch, ch) for ch in s)
    s = unicodedata.normalize("NFKD", s)
    s = re.sub(r"[^a-zA-Z0-9\u4e00-\u9fff ]", "", s)
    s = re.sub(
        r"\b(province|state|region|county|district|city|municipality|prefecture|department|"
        r"huyen|thi xa|thanh pho|xa|phuong|ward|commune)\b",
        "",
        s,
        flags=re.I,
    )
    return re.sub(r"\s+", " ", s).strip().lower()


def load_l4_index(cur) -> Dict[str, Dict[str, dict]]:
    """country -> norm_name -> L4 row"""
    cur.execute(
        """
        SELECT id, country_code, name, name_en, name_ch, level, path
        FROM geo_region WHERE status=1 AND level=4
        """
    )
    idx: Dict[str, Dict[str, dict]] = defaultdict(dict)
    for rid, cc, name, name_en, name_ch, level, path in cur.fetchall():
        cc = (cc or "").upper()
        row = {
            "id": int(rid),
            "country_code": cc,
            "level": int(level),
            "path": path or "",
            "name": name or "",
        }
        for raw in (name, name_en, name_ch):
            key = norm_name(raw)
            if key and key not in idx[cc]:
                idx[cc][key] = row
    return idx


def load_existing_source_ids(cur) -> Set[str]:
    cur.execute(
        """
        SELECT source_id FROM geo_region
        WHERE source='GEONAMES' AND source_id IS NOT NULL AND level=5
        """
    )
    return {str(r[0]) for r in cur.fetchall()}


def match_parent(l4_idx: Dict[str, dict], parent_name: str) -> Optional[dict]:
    key = norm_name(parent_name)
    if not key:
        return None
    hit = l4_idx.get(key)
    if hit:
        return hit
    key2 = key.replace(" ", "")
    for k, v in l4_idx.items():
        if k.replace(" ", "") == key2:
            return v
    return None


def stream_build_rows(
    txt_path: Path,
    l4_by_cc: Dict[str, Dict[str, dict]],
    existed: Set[str],
    only: Optional[Set[str]],
    next_id: int,
    limit: int,
) -> Tuple[List[dict], dict, int]:
    """流式：记录 ADM3 名称，ADM4 用 admin3 找父名，再匹配库内 L4。"""
    # (cc, admin3_code_or_geonameid) -> name
    adm3: Dict[Tuple[str, str], str] = {}
    to_insert: List[dict] = []
    stats = defaultdict(int)
    lines = 0

    with txt_path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            lines += 1
            if lines % 2_000_000 == 0:
                print(f"  scanned {lines:,} lines, matched={len(to_insert):,}", flush=True)
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 15:
                continue
            fcode = parts[7]
            iso2 = (parts[8] or "").upper()
            if not iso2 or iso2 in SKIP_COUNTRIES:
                continue
            if only and iso2 not in only:
                continue

            if fcode == "ADM3":
                name = parts[1] or parts[2]
                gid = parts[0]
                a3 = parts[12] or gid
                if name:
                    adm3[(iso2, gid)] = name
                    if a3:
                        adm3[(iso2, a3)] = name
                continue

            if fcode != "ADM4":
                continue

            source_id = parts[0]
            if source_id in existed:
                stats["dup"] += 1
                continue

            admin3 = parts[12] or ""
            parent_name = adm3.get((iso2, admin3)) or adm3.get((iso2, admin3.lstrip("0")))
            if not parent_name:
                stats["no_parent_name"] += 1
                continue

            parent = match_parent(l4_by_cc.get(iso2, {}), parent_name)
            if not parent:
                stats["parent_not_in_l4"] += 1
                continue

            try:
                lat = float(parts[4]) if parts[4] else None
                lng = float(parts[5]) if parts[5] else None
            except ValueError:
                lat = lng = None

            new_id = next_id
            next_id += 1
            parent_path = parent["path"] if parent["path"].endswith("/") else parent["path"] + "/"
            to_insert.append({
                "id": new_id,
                "parent_id": parent["id"],
                "country_code": parent["country_code"],
                "name": parts[1],
                "name_en": parts[2] or parts[1],
                "name_ch": None,
                "code": None,
                "level": parent["level"] + 1,
                "region_type": "WARD",
                "path": f"{parent_path}{new_id}/",
                "is_leaf": 1,
                "latitude": lat,
                "longitude": lng,
                "source": "GEONAMES",
                "source_id": source_id,
                "status": 1,
                "sort": 0,
                "_parent_name": parent_name,
                "_parent_l4": parent["name"],
            })
            existed.add(source_id)
            stats["matched"] += 1

            if limit > 0 and len(to_insert) >= limit:
                print(f"  reached --limit {limit} at line {lines:,}", flush=True)
                break

    stats["lines"] = lines
    return to_insert, dict(stats), next_id


def main() -> int:
    ap = argparse.ArgumentParser(description="L5 导入：父名匹配已有 L4")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--countries", default="")
    ap.add_argument("--file", default="")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--sample", type=int, default=10)
    args = ap.parse_args()

    only = {x.strip().upper() for x in args.countries.split(",") if x.strip()} or None
    txt = Path(args.file) if args.file else (GN / "allCountries.txt")
    if not args.file and not txt.exists():
        txt = Path("/Users/a0000/Downloads/allCountries.txt")

    hr("L5 Import · match existing L4", "=")
    print(f"  batch : {BATCH}")
    print(f"  mode  : {'APPLY' if args.apply else 'DRY-RUN'}")
    print(f"  file  : {txt}")
    print(f"  limit : {args.limit or 'ALL'}")
    print(f"  skip  : {','.join(sorted(SKIP_COUNTRIES))}")

    if not txt.exists():
        print(f"文件不存在: {txt}")
        return 1

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()
    l4_by_cc = load_l4_index(cur)
    existed = load_existing_source_ids(cur)
    cur.execute("SELECT COALESCE(MAX(id), %s - 1) FROM geo_region WHERE level=5", (500_000_000,))
    next_id = int(cur.fetchone()[0]) + 1
    print(f"  L4 countries: {len(l4_by_cc)}  existed L5 source_ids: {len(existed):,}  next_id: {next_id}")

    to_insert, stats, _ = stream_build_rows(
        txt, l4_by_cc, existed, only, next_id, args.limit
    )

    hr("结果")
    print(f"  待插入: {len(to_insert):,}  stats={stats}")
    for row in to_insert[: args.sample]:
        print(f"    · {row['name'][:28]:<28} parentL4={row['_parent_l4'][:20]:<20} "
              f"via[{row['_parent_name'][:20]}] id={row['id']} src={row['source_id']}")

    if not args.apply:
        print("\nDRY-RUN 完成。加 --apply 写库")
        conn.close()
        return 0
    if not to_insert:
        print("无数据可写")
        conn.close()
        return 0

    sql = """
    INSERT INTO geo_region
      (id, parent_id, country_code, name, name_en, name_ch, code, level, region_type, path, is_leaf,
       latitude, longitude, source, source_id, status, sort, created_at, updated_at)
    VALUES
      (%(id)s, %(parent_id)s, %(country_code)s, %(name)s, %(name_en)s, %(name_ch)s, %(code)s, %(level)s, %(region_type)s, %(path)s, %(is_leaf)s,
       %(latitude)s, %(longitude)s, %(source)s, %(source_id)s, %(status)s, %(sort)s, NOW(), NOW())
    """
    conn.begin()
    try:
        clean = [{k: v for k, v in r.items() if not k.startswith("_")} for r in to_insert]
        chunk = 1000
        for i in range(0, len(clean), chunk):
            cur.executemany(sql, clean[i:i + chunk])
            print(f"  inserted {min(i+chunk, len(clean)):,}/{len(clean):,}", flush=True)

        cur.execute(
            """
            UPDATE geo_region r
            LEFT JOIN (
              SELECT parent_id, COUNT(*) cnt FROM geo_region WHERE status=1 GROUP BY parent_id
            ) c ON r.id=c.parent_id
            SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
            """
        )
        cur.execute(
            """
            UPDATE geo_country c SET max_level=(
              SELECT COALESCE(MAX(r.level),1) FROM geo_region r
              WHERE r.country_code=c.iso2 AND r.status=1
            ) WHERE c.iso2 NOT IN ('CN')
            """
        )
        conn.commit()
        print(f"写库成功：{len(clean)} 条")
        cur.execute(
            "SELECT id,parent_id,country_code,name,level,path,source_id FROM geo_region WHERE id=%s",
            (clean[0]["id"],),
        )
        print("  校验首条:", cur.fetchone())
    except Exception as e:
        conn.rollback()
        print("失败:", e)
        raise
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

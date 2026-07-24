#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
16_audit_duplicates.py
======================
排查 geo_region 重复数据，输出汇总 + 样例。

维度：
  1) source + source_id（同源重复，最硬）
  2) parent_id + name（同父同名）
  3) parent_id + name_en
  4) country_code + level + name_en（同国同级英文名，可能误伤同名不同城）
  5) path 完全相同

用法:
  python3 16_audit_duplicates.py
  python3 16_audit_duplicates.py --sample 20 --out /tmp/geo_dups
"""

from __future__ import annotations

import argparse
import csv
import os
from collections import defaultdict
from pathlib import Path

import pymysql

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
}


CHECKS = [
    {
        "key": "source_id",
        "title": "同源 source+source_id+level 重复（真重复；不含 CSC 跨表同号）",
        "sql_groups": """
            SELECT source, source_id AS k2, level, COUNT(*) AS cnt,
                   GROUP_CONCAT(id ORDER BY id) AS ids
            FROM geo_region
            WHERE status=1 AND source_id IS NOT NULL AND source_id <> ''
            GROUP BY source, source_id, level
            HAVING COUNT(*) > 1
            ORDER BY cnt DESC, source, level, source_id
        """,
        "cols": ["source", "source_id", "level", "cnt", "ids"],
    },
    {
        "key": "source_id_cross_level",
        "title": "同源 source+source_id 跨 level 同号（多为 CSC country/state/city 撞号，一般不必删）",
        "sql_groups": """
            SELECT source, source_id AS k2, COUNT(*) AS cnt,
                   COUNT(DISTINCT level) AS levels,
                   GROUP_CONCAT(DISTINCT level ORDER BY level) AS level_list,
                   GROUP_CONCAT(id ORDER BY id) AS ids
            FROM geo_region
            WHERE status=1 AND source_id IS NOT NULL AND source_id <> ''
            GROUP BY source, source_id
            HAVING COUNT(*) > 1 AND COUNT(DISTINCT level) > 1
            ORDER BY cnt DESC, source, source_id
        """,
        "cols": ["source", "source_id", "cnt", "levels", "level_list", "ids"],
    },
    {
        "key": "parent_name",
        "title": "同父同名 parent_id+name",
        "sql_groups": """
            SELECT parent_id, name AS k2, country_code, level, COUNT(*) AS cnt,
                   GROUP_CONCAT(id ORDER BY id) AS ids
            FROM geo_region
            WHERE status=1
            GROUP BY parent_id, name, country_code, level
            HAVING COUNT(*) > 1
            ORDER BY cnt DESC, country_code, level
        """,
        "cols": ["parent_id", "name", "country_code", "level", "cnt", "ids"],
    },
    {
        "key": "parent_name_en",
        "title": "同父同英文名 parent_id+name_en",
        "sql_groups": """
            SELECT parent_id, name_en AS k2, country_code, level, COUNT(*) AS cnt,
                   GROUP_CONCAT(id ORDER BY id) AS ids
            FROM geo_region
            WHERE status=1 AND name_en IS NOT NULL AND name_en <> ''
            GROUP BY parent_id, name_en, country_code, level
            HAVING COUNT(*) > 1
            ORDER BY cnt DESC, country_code, level
        """,
        "cols": ["parent_id", "name_en", "country_code", "level", "cnt", "ids"],
    },
    {
        "key": "country_level_name_en",
        "title": "同国同级同英文名（可能含合理同名，仅供参考）",
        "sql_groups": """
            SELECT country_code, level, name_en AS k2, COUNT(*) AS cnt,
                   GROUP_CONCAT(id ORDER BY id) AS ids
            FROM geo_region
            WHERE status=1 AND name_en IS NOT NULL AND name_en <> ''
            GROUP BY country_code, level, name_en
            HAVING COUNT(*) > 1
            ORDER BY cnt DESC, country_code, level
        """,
        "cols": ["country_code", "level", "name_en", "cnt", "ids"],
    },
    {
        "key": "path",
        "title": "path 完全相同",
        "sql_groups": """
            SELECT path AS k2, COUNT(*) AS cnt, GROUP_CONCAT(id ORDER BY id) AS ids
            FROM geo_region
            WHERE status=1 AND path IS NOT NULL AND path <> ''
            GROUP BY path
            HAVING COUNT(*) > 1
            ORDER BY cnt DESC
        """,
        "cols": ["path", "cnt", "ids"],
    },
]


def hr(title: str = "", width: int = 72) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print("─" * left + f" {title} " + "─" * (pad - left))
    else:
        print("─" * width)


def main() -> int:
    ap = argparse.ArgumentParser(description="排查 geo_region 重复")
    ap.add_argument("--sample", type=int, default=15, help="每种维度打印样例组数")
    ap.add_argument("--out", default="", help="输出目录，写 CSV（每维度一个文件）")
    args = ap.parse_args()

    out_dir = Path(args.out) if args.out else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM geo_region WHERE status=1")
    total = cur.fetchone()[0]
    print(f"geo_region status=1 总量: {total:,}")
    print()

    summary = []
    for check in CHECKS:
        hr(check["title"])
        cur.execute(check["sql_groups"])
        rows = cur.fetchall()
        # also sum duplicate row count (extra rows beyond 1 per group)
        extra = 0
        for r in rows:
            # cnt is always near the end before ids for our queries
            cnt_idx = check["cols"].index("cnt")
            extra += int(r[cnt_idx]) - 1
        summary.append((check["key"], len(rows), extra))
        print(f"  重复组数: {len(rows):,}   多余行数(可删候选): {extra:,}")

        # print samples
        for r in rows[: args.sample]:
            parts = []
            for col, val in zip(check["cols"], r):
                if col == "ids" and val and len(str(val)) > 80:
                    val = str(val)[:77] + "..."
                parts.append(f"{col}={val}")
            print("   · " + " | ".join(parts))
        if len(rows) > args.sample:
            print(f"   … 另有 {len(rows) - args.sample:,} 组未展示")

        if out_dir:
            path = out_dir / f"dup_{check['key']}.csv"
            with path.open("w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(check["cols"])
                for r in rows:
                    w.writerow(list(r))
            print(f"  CSV → {path}")
        print()

    # per-level breakdown for hardest dups
    hr("按 level 看「同父同名」分布")
    cur.execute(
        """
        SELECT level, COUNT(*) groups_cnt, SUM(cnt-1) extra_rows
        FROM (
          SELECT level, parent_id, name, COUNT(*) cnt
          FROM geo_region WHERE status=1
          GROUP BY level, parent_id, name
          HAVING COUNT(*) > 1
        ) t
        GROUP BY level ORDER BY level
        """
    )
    for level, g, extra in cur.fetchall():
        print(f"  L{level}: 重复组 {g:,}  多余行 {int(extra):,}")

    hr("按 level 看「source+source_id+level」真重复分布")
    cur.execute(
        """
        SELECT level, COUNT(*) AS groups_cnt, SUM(cnt-1) AS extra_rows FROM (
          SELECT level, source, source_id, COUNT(*) cnt
          FROM geo_region
          WHERE status=1 AND source_id IS NOT NULL AND source_id <> ''
          GROUP BY level, source, source_id
          HAVING COUNT(*) > 1
        ) t GROUP BY level ORDER BY level
        """
    )
    rows = cur.fetchall()
    if not rows:
        print("  （无）")
    for level, g, extra in rows:
        print(f"  L{level}: 重复组 {g:,}  多余行 {int(extra):,}")

    hr("汇总")
    print(f"{'维度':<28} {'重复组':>10} {'多余行':>10}")
    for key, groups, extra in summary:
        print(f"{key:<28} {groups:>10,} {extra:>10,}")

    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
17_dedupe_same_parent_name.py
=============================
去除同父同名（parent_id + name）重复：
  - 每组保留 1 条（优先：子节点多 > 有坐标 > 有 name_ch > id 小）
  - 失败者的子节点挂到保留节点，并修正 path 前缀
  - 失败者 status=0（软删）
  - 多轮直到无剩余（合并父后可能产生新的同父同名子节点）
  - 最后重建 is_leaf

用法:
  python3 17_dedupe_same_parent_name.py              # dry-run
  python3 17_dedupe_same_parent_name.py --apply
  python3 17_dedupe_same_parent_name.py --apply --max-rounds 10
"""

from __future__ import annotations

import argparse
import os
from collections import defaultdict
from typing import Any, Dict, List, Tuple

import pymysql

DB_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "platform"),
    "password": os.environ.get("MYSQL_PASSWORD", "platform"),
    "database": os.environ.get("MYSQL_DB", "platform"),
    "charset": "utf8mb4",
    "autocommit": False,
}


def hr(title: str = "", width: int = 72) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print("─" * left + f" {title} " + "─" * (pad - left))
    else:
        print("─" * width)


def fetch_dup_groups(cur) -> List[Tuple[int, str, str, int]]:
    """Return list of (parent_id, name, country_code, level) for dup groups."""
    cur.execute(
        """
        SELECT parent_id, name, country_code, level, COUNT(*) AS cnt
        FROM geo_region
        WHERE status=1
        GROUP BY parent_id, name, country_code, level
        HAVING COUNT(*) > 1
        ORDER BY level ASC, country_code, parent_id, name
        """
    )
    return [(int(r[0]), r[1], r[2], int(r[3])) for r in cur.fetchall()]


def load_members(cur, parent_id: int, name: str) -> List[Dict[str, Any]]:
    cur.execute(
        """
        SELECT r.id, r.parent_id, r.name, r.level, r.country_code, r.path,
               r.latitude, r.name_ch, r.source, r.source_id,
               (SELECT COUNT(*) FROM geo_region c
                WHERE c.parent_id=r.id AND c.status=1) AS kids
        FROM geo_region r
        WHERE r.status=1 AND r.parent_id=%s AND r.name=%s
        ORDER BY r.id
        """,
        (parent_id, name),
    )
    cols = [
        "id", "parent_id", "name", "level", "country_code", "path",
        "latitude", "name_ch", "source", "source_id", "kids",
    ]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def pick_keeper(members: List[Dict[str, Any]]) -> Dict[str, Any]:
    def score(m: Dict[str, Any]) -> Tuple:
        return (
            int(m["kids"]),
            1 if m["latitude"] is not None else 0,
            1 if (m["name_ch"] or "").strip() else 0,
            -int(m["id"]),  # prefer smaller id → negate then max()
        )

    # max by score; for id we used -id so larger score = smaller id
    return max(members, key=score)


def normalize_path(p: str) -> str:
    if not p:
        return "/"
    return p if p.endswith("/") else p + "/"


def merge_group(cur, parent_id: int, name: str, dry_run: bool) -> Dict[str, int]:
    members = load_members(cur, parent_id, name)
    if len(members) < 2:
        return {"groups": 0, "soft_deleted": 0, "reparented": 0, "path_fixed": 0}

    keeper = pick_keeper(members)
    losers = [m for m in members if m["id"] != keeper["id"]]
    keeper_path = normalize_path(keeper["path"])

    soft_deleted = 0
    reparented = 0
    path_fixed = 0

    for loser in losers:
        loser_path = normalize_path(loser["path"])
        if dry_run:
            cur.execute(
                "SELECT COUNT(*) FROM geo_region WHERE status=1 AND parent_id=%s",
                (loser["id"],),
            )
            reparented += int(cur.fetchone()[0])
            cur.execute(
                """
                SELECT COUNT(*) FROM geo_region
                WHERE status=1 AND id<>%s AND path LIKE %s
                """,
                (loser["id"], loser_path + "%"),
            )
            path_fixed += int(cur.fetchone()[0])
            soft_deleted += 1
            continue

        # 1) reparent direct children
        cur.execute(
            "UPDATE geo_region SET parent_id=%s, updated_at=NOW() WHERE parent_id=%s AND status=1",
            (keeper["id"], loser["id"]),
        )
        reparented += cur.rowcount

        # 2) fix path prefix for all descendants (and former children)
        #    REPLACE path: loser_path → keeper_path
        if loser_path != keeper_path:
            cur.execute(
                """
                UPDATE geo_region
                SET path = CONCAT(%s, SUBSTRING(path, %s)),
                    updated_at = NOW()
                WHERE status=1
                  AND id <> %s
                  AND path LIKE %s
                """,
                (keeper_path, len(loser_path) + 1, loser["id"], loser_path + "%"),
            )
            path_fixed += cur.rowcount

        # 3) soft-delete loser
        cur.execute(
            "UPDATE geo_region SET status=0, is_leaf=1, updated_at=NOW() WHERE id=%s AND status=1",
            (loser["id"],),
        )
        soft_deleted += cur.rowcount

    return {
        "groups": 1,
        "soft_deleted": soft_deleted,
        "reparented": reparented,
        "path_fixed": path_fixed,
        "keeper_id": keeper["id"],
        "loser_ids": [m["id"] for m in losers],
        "level": keeper["level"],
        "country_code": keeper["country_code"],
    }


def rebuild_is_leaf(cur) -> None:
    cur.execute(
        """
        UPDATE geo_region r
        LEFT JOIN (
          SELECT parent_id, COUNT(*) AS cnt
          FROM geo_region WHERE status=1
          GROUP BY parent_id
        ) c ON r.id = c.parent_id
        SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt = 0 THEN 1 ELSE 0 END
        WHERE r.status = 1
        """
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="去除同父同名重复")
    ap.add_argument("--apply", action="store_true", help="真正写库（默认 dry-run）")
    ap.add_argument("--max-rounds", type=int, default=8, help="多轮合并上限")
    ap.add_argument("--sample", type=int, default=10, help="每轮打印样例组数")
    args = ap.parse_args()
    dry_run = not args.apply

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()

    cur.execute("SELECT COUNT(*) FROM geo_region WHERE status=1")
    before = int(cur.fetchone()[0])
    print(f"模式: {'DRY-RUN' if dry_run else 'APPLY'}")
    print(f"status=1 总量(前): {before:,}")

    totals = defaultdict(int)
    by_country = defaultdict(int)
    by_level = defaultdict(int)

    for round_no in range(1, args.max_rounds + 1):
        groups = fetch_dup_groups(cur)
        if not groups:
            print(f"\n第 {round_no} 轮：无同父同名重复，结束。")
            break

        hr(f"第 {round_no} 轮 · {len(groups):,} 组")
        round_stats = defaultdict(int)
        for i, (parent_id, name, cc, level) in enumerate(groups):
            # re-check: members may have changed mid-round
            members = load_members(cur, parent_id, name)
            if len(members) < 2:
                continue
            info = merge_group(cur, parent_id, name, dry_run=dry_run)
            if not info.get("groups"):
                continue
            round_stats["groups"] += 1
            round_stats["soft_deleted"] += info["soft_deleted"]
            round_stats["reparented"] += info["reparented"]
            round_stats["path_fixed"] += info["path_fixed"]
            by_country[cc] += info["soft_deleted"]
            by_level[level] += info["soft_deleted"]

            if i < args.sample:
                print(
                    f"  [{cc} L{level}] parent={parent_id} name={name!r} "
                    f"keep={info['keeper_id']} drop={info['loser_ids']} "
                    f"kids→{info['reparented']} pathfix={info['path_fixed']}"
                )

        for k, v in round_stats.items():
            totals[k] += v
        print(
            f"  本轮: 组={round_stats['groups']:,} 软删={round_stats['soft_deleted']:,} "
            f"改父={round_stats['reparented']:,} 修path={round_stats['path_fixed']:,}"
        )

        if dry_run:
            # dry-run 不写库，无法真正消重，只跑一轮预估
            print("  dry-run 只预估第 1 轮，停止。加 --apply 才会多轮写库。")
            break

        conn.commit()

    if not dry_run:
        hr("重建 is_leaf")
        rebuild_is_leaf(cur)
        conn.commit()
        print("  is_leaf 已重建")

    cur.execute(
        """
        SELECT COUNT(*) FROM (
          SELECT parent_id, name FROM geo_region WHERE status=1
          GROUP BY parent_id, name HAVING COUNT(*)>1
        ) t
        """
    )
    remain = int(cur.fetchone()[0]) if not dry_run else None
    cur.execute("SELECT COUNT(*) FROM geo_region WHERE status=1")
    after = int(cur.fetchone()[0])
    cur.execute("SELECT COUNT(*) FROM geo_region WHERE status=0")
    soft = int(cur.fetchone()[0])

    hr("汇总")
    print(f"  软删(本脚本累计预估/实际): {totals['soft_deleted']:,}")
    print(f"  改父: {totals['reparented']:,}  修path: {totals['path_fixed']:,}")
    print(f"  status=1 总量(后): {after:,}  (Δ {after - before:+,})")
    print(f"  status=0 总量: {soft:,}")
    if remain is not None:
        print(f"  剩余同父同名组: {remain:,}")

    if by_country:
        print("\n  按国软删 TOP:")
        for cc, n in sorted(by_country.items(), key=lambda x: -x[1])[:15]:
            print(f"    {cc}: {n:,}")
    if by_level:
        print("\n  按 level 软删:")
        for lv in sorted(by_level):
            print(f"    L{lv}: {by_level[lv]:,}")

    if dry_run:
        print("\n未写库。确认后执行: python3 17_dedupe_same_parent_name.py --apply")
    else:
        print("\n建议清 Redis 缓存: redis-cli --scan --pattern 'platform:geo:*' | xargs -r redis-cli del")

    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

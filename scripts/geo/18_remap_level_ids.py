#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
18_remap_level_ids.py
=====================
按层级重编码 geo_region.id，并同步更新所有 parent_id 与 path。

新规则（一眼能看出层级）：
  L1 国家：保持 1 ~ 250（不变）
  L2 省州：200_000_000 起自增
  L3 城市：300_000_000 起自增
  L4 区县：400_000_000 起自增
  L5 街镇：500_000_000 起自增

含 status=0 软删行（避免旧 id 占坑）。写库采用「建新表 → 校验 → RENAME 交换」。

用法:
  python3 18_remap_level_ids.py                 # dry-run
  python3 18_remap_level_ids.py --apply
  python3 18_remap_level_ids.py --apply --drop-backup   # 交换后删备份表
"""

from __future__ import annotations

import argparse
import os
from collections import defaultdict
from datetime import datetime, timezone
from typing import Dict, List, Tuple

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

# level -> first id (inclusive)
LEVEL_BASE = {
    2: 200_000_000,
    3: 300_000_000,
    4: 400_000_000,
    5: 500_000_000,
}
LEVEL_END = {  # exclusive upper bound for sanity
    2: 300_000_000,
    3: 400_000_000,
    4: 500_000_000,
    5: 600_000_000,
}

COLS = (
    "id", "parent_id", "country_code", "name", "name_en", "name_ch", "code",
    "level", "region_type", "path", "is_leaf", "latitude", "longitude",
    "source", "source_id", "status", "sort", "created_at", "updated_at",
)


def hr(title: str = "", width: int = 72) -> None:
    if title:
        pad = max(0, width - len(title) - 2)
        left = pad // 2
        print("─" * left + f" {title} " + "─" * (pad - left))
    else:
        print("─" * width)


def build_id_map(cur) -> Dict[int, int]:
    """old_id -> new_id；L1 恒等；L2~L5 按旧 id 升序密集分配。"""
    id_map: Dict[int, int] = {}

    cur.execute("SELECT id FROM geo_region WHERE level=1 ORDER BY id")
    for (oid,) in cur.fetchall():
        id_map[int(oid)] = int(oid)

    for level, base in LEVEL_BASE.items():
        cur.execute(
            "SELECT id FROM geo_region WHERE level=%s ORDER BY id ASC",
            (level,),
        )
        rows = cur.fetchall()
        end = LEVEL_END[level]
        if base + len(rows) > end:
            raise RuntimeError(
                f"L{level} 数量 {len(rows)} 超出号段 [{base}, {end})"
            )
        for i, (oid,) in enumerate(rows):
            id_map[int(oid)] = base + i
        print(
            f"  L{level}: {len(rows):,} 行 → "
            f"{base} .. {base + len(rows) - 1 if rows else '—'}"
        )
    return id_map


def rebuild_paths(
    rows: List[Tuple],
    id_map: Dict[int, int],
) -> Dict[int, str]:
    """基于新 id / 新 parent 重建 path。rows: 原始 COLS 顺序。"""
    # new_id -> (new_parent_id, level)
    nodes: Dict[int, Tuple[int, int]] = {}
    for r in rows:
        old_id = int(r[0])
        old_parent = int(r[1])
        level = int(r[7])
        new_id = id_map[old_id]
        if old_parent == 0:
            new_parent = 0
        else:
            if old_parent not in id_map:
                raise RuntimeError(
                    f"parent_id={old_parent} 不在映射中 (child old_id={old_id})"
                )
            new_parent = id_map[old_parent]
        nodes[new_id] = (new_parent, level)

    paths: Dict[int, str] = {}

    def path_of(nid: int) -> str:
        if nid in paths:
            return paths[nid]
        parent, level = nodes[nid]
        if parent == 0:
            p = f"/{nid}/"
        else:
            if parent not in nodes:
                raise RuntimeError(f"新 parent {parent} 缺失 (node={nid})")
            p = path_of(parent) + f"{nid}/"
        paths[nid] = p
        if len(p) > 256:
            raise RuntimeError(f"path 超长 {len(p)}: {p}")
        return p

    for nid in nodes:
        path_of(nid)
    return paths


def verify_plan(cur, id_map: Dict[int, int]) -> None:
    # target bands empty?
    for level, base in LEVEL_BASE.items():
        end = LEVEL_END[level]
        cur.execute(
            "SELECT COUNT(*) FROM geo_region WHERE id >= %s AND id < %s",
            (base, end),
        )
        n = int(cur.fetchone()[0])
        if n:
            raise RuntimeError(
                f"目标号段 [{base},{end}) 已有 {n} 行，无法直接映射"
            )

    # new ids unique
    news = list(id_map.values())
    if len(news) != len(set(news)):
        raise RuntimeError("新 id 有重复")

    # every row mapped
    cur.execute("SELECT COUNT(*) FROM geo_region")
    total = int(cur.fetchone()[0])
    if len(id_map) != total:
        raise RuntimeError(f"映射数 {len(id_map)} != 总行数 {total}")


def main() -> int:
    ap = argparse.ArgumentParser(description="按层级重编码 id/parent_id/path")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--drop-backup", action="store_true", help="成功后删除备份表")
    ap.add_argument("--sample", type=int, default=8)
    args = ap.parse_args()
    dry = not args.apply
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    bak = f"geo_region_bak_{stamp}"
    tmp = f"geo_region_new_{stamp}"

    conn = pymysql.connect(**DB_CONFIG)
    cur = conn.cursor()

    hr("ID 层级重编码")
    print(f"  模式: {'DRY-RUN' if dry else 'APPLY'}")
    for lv, base in LEVEL_BASE.items():
        print(f"  L{lv} base = {base}")

    hr("1. 构建 old→new 映射")
    id_map = build_id_map(cur)
    verify_plan(cur, id_map)
    print(f"  映射总数: {len(id_map):,}")

    hr("2. 加载全表并重建 path")
    cur.execute(f"SELECT {', '.join(COLS)} FROM geo_region ORDER BY level, id")
    rows = cur.fetchall()
    new_paths = rebuild_paths(rows, id_map)
    print(f"  path 已重建: {len(new_paths):,}")

    # samples
    hr("样例 old → new (含 parent)")
    shown = 0
    for r in rows:
        old_id = int(r[0])
        level = int(r[7])
        if level == 1:
            continue
        old_parent = int(r[1])
        new_id = id_map[old_id]
        new_parent = 0 if old_parent == 0 else id_map[old_parent]
        print(
            f"  L{level} {old_id}→{new_id}  parent {old_parent}→{new_parent}  "
            f"path {new_paths[new_id]}"
        )
        shown += 1
        if shown >= args.sample:
            break

    # stats by level
    hr("号段占用预估")
    by_lv = defaultdict(int)
    for r in rows:
        by_lv[int(r[7])] += 1
    for lv in sorted(by_lv):
        if lv == 1:
            print(f"  L1: 保持原 id，共 {by_lv[lv]}")
        else:
            b = LEVEL_BASE[lv]
            print(f"  L{lv}: {b} .. {b + by_lv[lv] - 1}  ({by_lv[lv]:,} 行)")

    if dry:
        print("\nDRY-RUN 完成，未写库。确认后: python3 18_remap_level_ids.py --apply")
        conn.close()
        return 0

    hr("3. 写入临时表 " + tmp)
    cur.execute(f"DROP TABLE IF EXISTS `{tmp}`")
    cur.execute(f"CREATE TABLE `{tmp}` LIKE geo_region")

    insert_sql = (
        f"INSERT INTO `{tmp}` ({', '.join(COLS)}) VALUES ("
        + ", ".join(["%s"] * len(COLS))
        + ")"
    )
    batch = []
    batch_size = 2000
    written = 0
    for r in rows:
        old_id = int(r[0])
        old_parent = int(r[1])
        new_id = id_map[old_id]
        new_parent = 0 if old_parent == 0 else id_map[old_parent]
        new_row = list(r)
        new_row[0] = new_id
        new_row[1] = new_parent
        new_row[9] = new_paths[new_id]
        batch.append(tuple(new_row))
        if len(batch) >= batch_size:
            cur.executemany(insert_sql, batch)
            written += len(batch)
            batch.clear()
            if written % 50000 == 0:
                print(f"  inserted {written:,}/{len(rows):,}", flush=True)
    if batch:
        cur.executemany(insert_sql, batch)
        written += len(batch)
    print(f"  临时表写入: {written:,}")

    hr("4. 校验临时表")
    cur.execute(f"SELECT COUNT(*) FROM `{tmp}`")
    if int(cur.fetchone()[0]) != len(rows):
        conn.rollback()
        raise RuntimeError("临时表行数不一致")

    # orphans
    cur.execute(
        f"""
        SELECT COUNT(*) FROM `{tmp}` c
        LEFT JOIN `{tmp}` p ON p.id = c.parent_id
        WHERE c.parent_id <> 0 AND p.id IS NULL
        """
    )
    orphans = int(cur.fetchone()[0])
    if orphans:
        conn.rollback()
        raise RuntimeError(f"临时表存在 {orphans} 个孤儿 parent")

    # band check
    for level, base in LEVEL_BASE.items():
        end = LEVEL_END[level]
        cur.execute(
            f"SELECT COUNT(*) FROM `{tmp}` WHERE level=%s AND (id < %s OR id >= %s)",
            (level, base, end),
        )
        bad = int(cur.fetchone()[0])
        if bad:
            conn.rollback()
            raise RuntimeError(f"临时表 L{level} 有 {bad} 行越界")

    # path suffix = /{{id}}/
    cur.execute(
        f"""
        SELECT COUNT(*) FROM `{tmp}`
        WHERE path NOT LIKE CONCAT('%/', id, '/')
           OR path NOT LIKE '/%'
        """
    )
    bad_path = int(cur.fetchone()[0])
    if bad_path:
        conn.rollback()
        raise RuntimeError(f"临时表 path 与 id 不一致: {bad_path}")

    # parent_id 指向的节点存在且 level = child.level-1（L1 parent=0）
    cur.execute(
        f"""
        SELECT COUNT(*) FROM `{tmp}` c
        JOIN `{tmp}` p ON p.id = c.parent_id
        WHERE c.level > 1 AND p.level <> c.level - 1
        """
    )
    bad_lv = int(cur.fetchone()[0])
    if bad_lv:
        print(f"  警告: {bad_lv} 条父子 level 差不为 1（可能历史挂载，不阻断）")

    print("  校验通过")

    hr("5. RENAME 交换")
    cur.execute(f"RENAME TABLE geo_region TO `{bak}`, `{tmp}` TO geo_region")
    conn.commit()
    print(f"  当前表: geo_region")
    print(f"  备份表: {bak}")

    # post checks on live table
    hr("6. 线上抽检")
    cur.execute(
        """
        SELECT level, COUNT(*), MIN(id), MAX(id)
        FROM geo_region GROUP BY level ORDER BY level
        """
    )
    for level, cnt, mn, mx in cur.fetchall():
        print(f"  L{level}: n={cnt:,}  id={mn}..{mx}")

    cur.execute(
        """
        SELECT COUNT(*) FROM geo_region c
        LEFT JOIN geo_region p ON p.id=c.parent_id
        WHERE c.parent_id<>0 AND p.id IS NULL
        """
    )
    print(f"  orphans: {int(cur.fetchone()[0])}")

    cur.execute(
        "SELECT id, parent_id, level, path FROM geo_region WHERE level=5 AND status=1 ORDER BY id LIMIT 2"
    )
    for r in cur.fetchall():
        print(f"  sample L5: {r}")

    if args.drop_backup:
        cur.execute(f"DROP TABLE `{bak}`")
        conn.commit()
        print(f"  已删除备份 {bak}")
    else:
        print(f"  保留备份。确认无误后: DROP TABLE `{bak}`;")

    print("\n建议清 Redis: redis-cli KEYS 'platform:geo:*' | xargs -r redis-cli DEL")
    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

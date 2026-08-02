#!/usr/bin/env python3
"""
通过 GeoNames 补全越南 L3/L4/L5 数据

流程:
  1. 找出缺 L3 的省
  2. 从 VN.txt 提取对应 admin1 的 ADM2 → L3
  3. 从 VN.txt 提取对应 admin1 的 ADM3 → L4
  4. 提取 PPLX → L5
  5. 更新 is_leaf
"""

import pymysql
import unicodedata
import re
from collections import defaultdict
from pathlib import Path

RAW = Path("/Users/a0000/IdeaProjects/platform/scripts/geo/raw")
COUNTRY_CODE = "VN"
COUNTRY_ID = 240

DB = {"host": "127.0.0.1", "port": 3306, "user": "root", "database": "platform", "charset": "utf8mb4"}

ID_BASE = {
    2: 1000000,  # Province
    3: 300000000,  # District
    4: 400000000,  # Commune
    5: 500000000,  # Ward
}


def strip_diacritics(s):
    if not s: return ""
    cm = {'Đ': 'D', 'đ': 'd'}
    s = ''.join(cm.get(c, c) for c in s)
    return re.sub(r'[^a-zA-Z0-9 ]', '', unicodedata.normalize('NFKD', s)).strip().lower()


def clean_name(name):
    """清理名称，去除类型后缀"""
    name = re.sub(r'\s*\(.*?\)\s*$', '', name).strip()
    name = re.sub(r'\s*(Province|City|Municipality)\s*$', '', name).strip()
    return name


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    conn.autocommit(False)

    # 1. 加载现有省
    cur.execute("SELECT id, code, name FROM geo_region WHERE country_code='VN' AND level=2")
    provinces = {}
    for pid, code, name in cur.fetchall():
        local_code = code[3:] if code and code.startswith('VN-') else (code or '')
        provinces[local_code] = {"id": pid, "name": name}

    print(f"现有省: {len(provinces)}")

    # 2. 加载现有所有 ID 防冲突
    cur.execute("SELECT id FROM geo_region")
    used_ids = {r[0] for r in cur.fetchall()}
    print(f"现有记录: {len(used_ids)}")

    # 3. 解析 VN.txt，按 admin1 code 和 feature code 分组
    adm2 = defaultdict(list)
    adm3 = defaultdict(list)
    pplx = defaultdict(list)

    with open(RAW / "VN.txt", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) < 12:
                continue
            fcode = parts[7]
            a1 = parts[10]
            rec = {
                "gid": int(parts[0]),
                "name": parts[1],
                "ascii": parts[2],
                "lat": float(parts[4]) if parts[4] else None,
                "lng": float(parts[5]) if parts[5] else None,
            }
            if fcode == "ADM2":
                adm2[a1].append(rec)
            elif fcode == "ADM3":
                adm3[a1].append(rec)
            elif fcode == "PPLX":
                pplx[a1].append(rec)

    # 4. 找缺 L3 的省
    cur.execute("""
        SELECT p.id, p.code, p.name
        FROM geo_region p
        WHERE p.country_code='VN' AND p.level=2
        AND NOT EXISTS (SELECT 1 FROM geo_region c WHERE c.parent_id=p.id AND c.level=3)
    """)
    missing = cur.fetchall()
    print(f"缺 L3 的省: {len(missing)}")

    # 5. 逐省补 L3
    def next_id(level):
        base = ID_BASE.get(level, 400000000)
        nid = base
        while nid in used_ids:
            nid += 1
        used_ids.add(nid)
        return nid

    inserted = {2: 0, 3: 0, 4: 0, 5: 0}

    for pid, code, name in missing:
        local_code = code[3:] if code and code.startswith('VN-') else (code or '')
        prov_adm2 = adm2.get(local_code, [])
        if not prov_adm2:
            # 尝试通过名称匹配
            name_norm = strip_diacritics(clean_name(name))
            for a1_code, records in adm2.items():
                # 找第一个 ADM2 记录的父级 admin1
                pass
            continue

        print(f"\n  {name} (code={local_code}): {len(prov_adm2)} ADM2")

        for r in prov_adm2:
            nid = next_id(3)
            try:
                cur.execute(
                    """INSERT INTO geo_region (id, parent_id, country_code, name, name_en, level, region_type, path, is_leaf, latitude, longitude, source, source_id, status, sort, created_at, updated_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'GEONAMES',%s,1,0,NOW(),NOW())""",
                    (nid, pid, COUNTRY_CODE, r["name"], r["ascii"], 3, "DISTRICT",
                     f"/{COUNTRY_ID}/{pid}/{nid}/", 0, r["lat"], r["lng"], str(r["gid"]))
                )
                inserted[3] += 1
            except Exception as e:
                print(f"    L3 插入失败: {r['name']} - {e}")

    conn.commit()
    print(f"\nL3 新增: {inserted[3]}")

    # 6. 补 L4
    for pid, code, name in missing:
        local_code = code[3:] if code and code.startswith('VN-') else (code or '')
        prov_adm3 = adm3.get(local_code, [])
        if not prov_adm3:
            continue

        print(f"  {name}: {len(prov_adm3)} ADM3 -> L4")

        for r in prov_adm3:
            nid = next_id(4)
            try:
                cur.execute(
                    """INSERT INTO geo_region (id, parent_id, country_code, name, name_en, level, region_type, path, is_leaf, latitude, longitude, source, source_id, status, sort, created_at, updated_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'GEONAMES',%s,1,0,NOW(),NOW())""",
                    (nid, pid, COUNTRY_CODE, r["name"], r["ascii"], 4, "DISTRICT",
                     f"/{COUNTRY_ID}/{pid}/{nid}/", 1, r["lat"], r["lng"], str(r["gid"]))
                )
                inserted[4] += 1
            except Exception as e:
                print(f"    L4 插入失败: {r['name']} - {e}")

    conn.commit()
    print(f"L4 新增: {inserted[4]}")

    # 7. 补 L5 (PPLX)
    for pid, code, name in missing:
        local_code = code[3:] if code and code.startswith('VN-') else (code or '')
        prov_pplx = pplx.get(local_code, [])
        if not prov_pplx:
            continue
        print(f"  {name}: {len(prov_pplx)} PPLX -> L5")
        for r in prov_pplx:
            nid = next_id(5)
            try:
                cur.execute(
                    """INSERT INTO geo_region (id, parent_id, country_code, name, name_en, level, region_type, path, is_leaf, latitude, longitude, source, source_id, status, sort, created_at, updated_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'GEONAMES',%s,1,0,NOW(),NOW())""",
                    (nid, pid, COUNTRY_CODE, r["name"], r["ascii"], 5, "WARD",
                     f"/{COUNTRY_ID}/{pid}/{nid}/", 1, r["lat"], r["lng"], str(r["gid"]))
                )
                inserted[5] += 1
            except Exception as e:
                print(f"    L5 插入失败: {r['name']} - {e}")

    conn.commit()

    # 8. 更新 is_leaf
    cur.execute("""
        UPDATE geo_region r
        LEFT JOIN (SELECT parent_id, COUNT(*) cnt FROM geo_region WHERE country_code='VN' AND level=3 GROUP BY parent_id) c
          ON r.id = c.parent_id
        SET r.is_leaf = CASE WHEN c.cnt IS NULL OR c.cnt=0 THEN 1 ELSE 0 END
        WHERE r.country_code='VN' AND r.level=2
    """)
    conn.commit()

    # 9. 汇总
    print(f"\n{'='*50}")
    print(f"  补全完成")
    print(f"{'='*50}")
    print(f"  L3 新增: {inserted[3]}")
    print(f"  L4 新增: {inserted[4]}")
    print(f"  L5 新增: {inserted[5]}")

    cur.execute("SELECT level, region_type, COUNT(*) FROM geo_region WHERE country_code='VN' GROUP BY level, region_type ORDER BY level")
    for row in cur.fetchall():
        print(f"  Level {row[0]} ({row[1]:15s}): {row[2]:,}")

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()

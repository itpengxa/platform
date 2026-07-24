#!/usr/bin/env python3
"""
2026-07-24 GEO-001
将 Google 校验通过的越南数据 + 校验日志写入本地 MySQL。

默认连接: unix_socket / root 无密码 / database=platform
可用环境变量覆盖: MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DB

仅写入 overall_ok=1 的区划；国家行在 ok 集合含 level=1 时写入 geo_country。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CLEAN = ROOT / "clean"
VALIDATED = ROOT / "validated"


def mysql_exec(sql: str, user: str, password: str, host: str, port: str, db: str):
    cmd = ["mysql", f"-u{user}", "-h", host, "-P", port, db, "-e", sql]
    if password:
        cmd.insert(2, f"-p{password}")
    env = os.environ.copy()
    subprocess.run(cmd, check=True, env=env)


def mysql_cli_base(user, password, host, port, db):
    cmd = ["mysql", f"-u{user}", "-h", host, "-P", str(port), db]
    if password:
        cmd.insert(2, f"-p{password}")
    # 本机 socket：若 host=127.0.0.1 且无密码，改用默认 socket 更稳
    if host in ("127.0.0.1", "localhost") and not password:
        cmd = ["mysql", f"-u{user}", db]
    return cmd


def esc(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return str(v)
    s = str(v).replace("\\", "\\\\").replace("'", "\\'")
    return f"'{s}'"


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    if not path.exists():
        return rows
    with path.open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default=os.environ.get("MYSQL_HOST", "127.0.0.1"))
    p.add_argument("--port", default=os.environ.get("MYSQL_PORT", "3306"))
    p.add_argument("--user", default=os.environ.get("MYSQL_USER", "root"))
    p.add_argument("--password", default=os.environ.get("MYSQL_PASSWORD", ""))
    p.add_argument("--db", default=os.environ.get("MYSQL_DB", "platform"))
    p.add_argument("--replace-vn", action="store_true", help="落库前删除已有 VN 数据")
    args = p.parse_args()

    country = json.loads((CLEAN / "vn_country.json").read_text(encoding="utf-8"))
    ok_regions = load_jsonl(VALIDATED / "vn_regions_ok.jsonl")
    logs = load_jsonl(VALIDATED / "vn_validation_log.jsonl")
    if not ok_regions:
        raise SystemExit("没有校验通过的数据: validated/vn_regions_ok.jsonl")

    ok_ids = {r["id"] for r in ok_regions}
    # 只保留祖先也在 ok 集合中的节点（或国家）
    # 先确保国家节点存在
    country_node = next((r for r in ok_regions if r.get("level") == 1), None)
    if country_node is None:
        raise SystemExit("校验通过集合缺少国家节点(level=1)，请把 level=1 纳入校验")

    # 过滤：父链完整
    by_id = {r["id"]: r for r in ok_regions}
    final_regions = []
    for r in ok_regions:
        if r["level"] == 1:
            final_regions.append(r)
            continue
        pid = r.get("parent_id")
        if pid in by_id or pid == country["id"]:
            # parent must be ok (or country)
            if pid == country["id"] or pid in ok_ids:
                final_regions.append(r)

    # 重建 is_leaf
    children = set()
    for r in final_regions:
        if r.get("parent_id"):
            children.add(r["parent_id"])
    for r in final_regions:
        if r["level"] < 3:
            r["is_leaf"] = 0 if r["id"] in children else 1
        else:
            r["is_leaf"] = 1

    sql_path = VALIDATED / "vn_load.sql"
    lines = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    if args.replace_vn:
        lines.append("DELETE FROM geo_region WHERE country_code='VN';")
        lines.append("DELETE FROM geo_country WHERE iso2='VN';")

    # country
    c = country
    lines.append(
        "INSERT INTO geo_country (id,iso2,iso3,name,name_en,name_ch,icon_base64,phone_code,currency_code,max_level,status,sort) VALUES ("
        f"{esc(c['id'])},{esc(c['iso2'])},{esc(c['iso3'])},{esc(c['name'])},{esc(c['name_en'])},{esc(c['name_ch'])},"
        f"NULL,{esc(c.get('phone_code'))},{esc(c.get('currency_code'))},{esc(c.get('max_level',3))},1,0"
        ") ON DUPLICATE KEY UPDATE name=VALUES(name), name_en=VALUES(name_en), name_ch=VALUES(name_ch), "
        "phone_code=VALUES(phone_code), currency_code=VALUES(currency_code), max_level=VALUES(max_level), status=1;"
    )

    for r in final_regions:
        lines.append(
            "INSERT INTO geo_region (id,parent_id,country_code,name,name_en,name_ch,code,level,region_type,path,"
            "is_leaf,latitude,longitude,source,source_id,status,sort) VALUES ("
            f"{esc(r['id'])},{esc(r['parent_id'])},{esc(r['country_code'])},{esc(r['name'])},{esc(r.get('name_en'))},"
            f"{esc(r.get('name_ch'))},{esc(r.get('code'))},{esc(r['level'])},{esc(r['region_type'])},{esc(r['path'])},"
            f"{esc(r.get('is_leaf',1))},{esc(r.get('latitude'))},{esc(r.get('longitude'))},{esc(r.get('source'))},"
            f"{esc(r.get('source_id'))},1,{esc(r.get('sort',0))}"
            ") ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), name_en=VALUES(name_en), "
            "name_ch=VALUES(name_ch), path=VALUES(path), is_leaf=VALUES(is_leaf), latitude=VALUES(latitude), "
            "longitude=VALUES(longitude), source=VALUES(source), status=1;"
        )

    # validation logs
    for log in logs:
        lines.append(
            "INSERT INTO geo_validation_log (batch_id,country_code,source,source_id,region_temp_id,level,"
            "input_name,input_address,input_lat,input_lng,google_status,google_formatted_address,"
            "google_lat,google_lng,google_place_id,distance_meters,address_ok,latlng_ok,overall_ok,fail_reason,raw_response) VALUES ("
            f"{esc(log.get('batch_id'))},{esc(log.get('country_code'))},{esc(log.get('source'))},{esc(log.get('source_id'))},"
            f"{esc(log.get('region_temp_id'))},{esc(log.get('level'))},{esc(log.get('input_name'))},{esc(log.get('input_address'))},"
            f"{esc(log.get('input_lat'))},{esc(log.get('input_lng'))},{esc(log.get('google_status'))},"
            f"{esc(log.get('google_formatted_address'))},{esc(log.get('google_lat'))},{esc(log.get('google_lng'))},"
            f"{esc(log.get('google_place_id'))},{esc(log.get('distance_meters'))},{esc(log.get('address_ok',0))},"
            f"{esc(log.get('latlng_ok',0))},{esc(log.get('overall_ok',0))},{esc(log.get('fail_reason'))},"
            f"{esc(log.get('raw_response'))}"
            ");"
        )

    lines.append("COMMIT;")
    sql_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {sql_path} regions={len(final_regions)} logs={len(logs)}")

    # ensure validation table
    schema = Path(__file__).resolve().parents[2] / "sql" / "geo_validation_log.sql"
    base = mysql_cli_base(args.user, args.password, args.host, args.port, args.db)
    subprocess.run(base + ["-e", "source " + str(schema)], check=False)

    with sql_path.open(encoding="utf-8") as f:
        proc = subprocess.run(base, stdin=f, capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stderr)
        raise SystemExit(proc.returncode)
    print("LOAD OK")
    subprocess.run(base + ["-e",
                           "SELECT COUNT(*) AS countries FROM geo_country WHERE iso2='VN';"
                           "SELECT COUNT(*) AS regions FROM geo_region WHERE country_code='VN';"
                           "SELECT overall_ok, COUNT(*) cnt FROM geo_validation_log WHERE country_code='VN' GROUP BY overall_ok;"],
                   check=False)


if __name__ == "__main__":
    main()

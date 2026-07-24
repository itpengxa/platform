#!/usr/bin/env python3
"""
2026-07-24 GEO-001
清洗越南数据 → 统一 geo_country / geo_region 结构（JSONL）

输入: scripts/geo/raw/
输出: scripts/geo/clean/vn_country.json
      scripts/geo/clean/vn_regions.jsonl
      scripts/geo/clean/vn_summary.json
"""
from __future__ import annotations

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
CLEAN = ROOT / "clean"
CLEAN.mkdir(parents=True, exist_ok=True)

COUNTRY_CODE = "VN"


def _f(v):
    if v is None or v == "":
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _zh(translations: dict | None) -> str | None:
    if not translations:
        return None
    return translations.get("zh-CN") or translations.get("zh") or translations.get("cn")


def load_geonames_index(path: Path) -> dict[str, tuple[float, float]]:
    """name_lower -> (lat, lng)，优先 ADM1/ADM2/PPLA*"""
    idx: dict[str, tuple[float, float, int]] = {}
    # feature class priority: P (populated) > A (admin)
    prio = {"PPLA": 100, "PPLA2": 90, "PPLA3": 80, "PPLC": 95, "ADM1": 70, "ADM2": 60, "ADM3": 50}
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 11:
                continue
            name, asciiname = parts[1], parts[2]
            lat, lng = _f(parts[4]), _f(parts[5])
            fcode = parts[7]
            if lat is None or lng is None:
                continue
            score = prio.get(fcode, 10)
            for n in {name, asciiname}:
                if not n:
                    continue
                key = n.strip().lower()
                old = idx.get(key)
                if old is None or score > old[2]:
                    idx[key] = (lat, lng, score)
    return {k: (v[0], v[1]) for k, v in idx.items()}


def enrich_latlng(name: str, lat, lng, geo_idx):
    if lat is not None and lng is not None:
        return lat, lng, "CSC"
    hit = geo_idx.get((name or "").strip().lower())
    if hit:
        return hit[0], hit[1], "GEONAMES"
    return None, None, None


def main():
    countries = json.loads((RAW / "countries.json").read_text(encoding="utf-8"))
    states = json.loads((RAW / "states.json").read_text(encoding="utf-8"))
    cities = json.loads((RAW / "cities_VN.json").read_text(encoding="utf-8"))
    geo_idx = load_geonames_index(RAW / "VN.txt")

    vn = next(c for c in countries if c.get("iso2") == COUNTRY_CODE)
    country_id = int(vn["id"])  # 240

    lat, lng, _ = enrich_latlng(vn.get("native") or vn["name"], _f(vn.get("latitude")), _f(vn.get("longitude")), geo_idx)
    country = {
        "id": country_id,
        "iso2": "VN",
        "iso3": vn.get("iso3") or "VNM",
        "name": vn.get("native") or "Việt Nam",
        "name_en": vn.get("name") or "Vietnam",
        "name_ch": _zh(vn.get("translations")) or "越南",
        "icon_base64": None,
        "phone_code": ("+" + str(vn["phonecode"])) if vn.get("phonecode") else "+84",
        "currency_code": vn.get("currency") or "VND",
        "max_level": 5,
        "status": 1,
        "sort": 0,
    }

    vn_states = [s for s in states if s.get("country_code") == "VN" or s.get("country_id") == country_id]
    regions = []

    # level 1 country node
    regions.append({
        "id": country_id,
        "parent_id": 0,
        "country_code": "VN",
        "name": country["name"],
        "name_en": country["name_en"],
        "name_ch": country["name_ch"],
        "code": "VN",
        "level": 1,
        "region_type": "COUNTRY",
        "path": f"/{country_id}/",
        "is_leaf": 0,
        "latitude": lat,
        "longitude": lng,
        "source": "CSC",
        "source_id": str(vn["id"]),
        "status": 1,
        "sort": 0,
        "address_query": "Vietnam",
    })

    state_by_id = {}
    for i, s in enumerate(sorted(vn_states, key=lambda x: (x.get("name") or ""))):
        sid = int(s["id"])
        slat, slng, src = enrich_latlng(
            s.get("native") or s.get("name"),
            _f(s.get("latitude")),
            _f(s.get("longitude")),
            geo_idx,
        )
        stype = (s.get("type") or "province").lower()
        region_type = "PROVINCE" if "prov" in stype else "STATE"
        name_local = s.get("native") or s.get("name")
        name_en = s.get("name")
        name_ch = _zh(s.get("translations"))
        path = f"/{country_id}/{sid}/"
        row = {
            "id": sid,
            "parent_id": country_id,
            "country_code": "VN",
            "name": name_local,
            "name_en": name_en,
            "name_ch": name_ch,
            "code": s.get("iso3166_2") or s.get("iso2"),
            "level": 2,
            "region_type": region_type,
            "path": path,
            "is_leaf": 0,
            "latitude": slat,
            "longitude": slng,
            "source": "CSC" if src != "GEONAMES" else "CSC+GEONAMES",
            "source_id": str(sid),
            "status": 1,
            "sort": i,
            "address_query": f"{name_en}, Vietnam",
            "_lat_source": src,
        }
        regions.append(row)
        state_by_id[sid] = row

    city_count_by_state: dict[int, int] = {sid: 0 for sid in state_by_id}
    for c in cities:
        if c.get("country_code") != "VN" and c.get("country_id") != country_id:
            continue
        cid = int(c["id"])
        parent_id = int(c.get("state_id") or 0)
        parent = state_by_id.get(parent_id)
        if parent is None:
            # 挂到国家下，标记异常但仍输出供校验
            parent_id = country_id
            path = f"/{country_id}/{cid}/"
            level = 3
        else:
            path = f"{parent['path']}{cid}/"
            level = 3
            city_count_by_state[parent_id] = city_count_by_state.get(parent_id, 0) + 1

        clat, clng, src = enrich_latlng(
            c.get("native") or c.get("name"),
            _f(c.get("latitude")),
            _f(c.get("longitude")),
            geo_idx,
        )
        name_local = c.get("native") or c.get("name")
        name_en = c.get("name")
        name_ch = _zh(c.get("translations"))
        parent_name = parent["name_en"] if parent else "Vietnam"
        regions.append({
            "id": cid,
            "parent_id": parent_id,
            "country_code": "VN",
            "name": name_local,
            "name_en": name_en,
            "name_ch": name_ch,
            "code": None,
            "level": level,
            "region_type": "CITY",
            "path": path,
            "is_leaf": 1,
            "latitude": clat,
            "longitude": clng,
            "source": "CSC" if src != "GEONAMES" else "CSC+GEONAMES",
            "source_id": str(cid),
            "status": 1,
            "sort": city_count_by_state.get(parent_id, 0),
            "address_query": f"{name_en}, {parent_name}, Vietnam",
            "_lat_source": src,
        })

    # 无子节点的省 → is_leaf=1
    parents_with_child = {r["parent_id"] for r in regions if r["level"] == 3}
    for r in regions:
        if r["level"] == 2 and r["id"] not in parents_with_child:
            r["is_leaf"] = 1

    (CLEAN / "vn_country.json").write_text(json.dumps(country, ensure_ascii=False, indent=2), encoding="utf-8")
    with (CLEAN / "vn_regions.jsonl").open("w", encoding="utf-8") as out:
        for r in regions:
            out.write(json.dumps(r, ensure_ascii=False) + "\n")

    summary = {
        "country_code": "VN",
        "country_id": country_id,
        "states": len(vn_states),
        "cities": sum(1 for r in regions if r["level"] == 3),
        "regions_total": len(regions),
        "geonames_name_index": len(geo_idx),
        "missing_latlng": sum(1 for r in regions if r.get("latitude") is None),
    }
    (CLEAN / "vn_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

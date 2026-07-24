#!/usr/bin/env python3
"""
2026-07-24 GEO-001
校验越南清洗数据的地址与经纬度。

默认 provider=nominatim（OpenStreetMap，免费）
可选 provider=google（需 GOOGLE_MAPS_API_KEY）

用法:
  python3 03_validate_google_vn.py --limit 20
  python3 03_validate_google_vn.py --provider nominatim
  python3 03_validate_google_vn.py --provider google --api-key KEY

Nominatim 使用政策: ≤1 次/秒，必须设置可识别 User-Agent。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CLEAN = ROOT / "clean"
VALIDATED = ROOT / "validated"
LOGS = ROOT / "logs"
VALIDATED.mkdir(parents=True, exist_ok=True)
LOGS.mkdir(parents=True, exist_ok=True)

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
GOOGLE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
USER_AGENT = "platform-geo-etl/1.0 (caopan platform; contact: local-dev)"


def haversine_m(lat1, lon1, lat2, lon2) -> float | None:
    if None in (lat1, lon1, lat2, lon2):
        return None
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def in_vietnam_bounds(lat, lng) -> bool:
    return 8.0 <= lat <= 24.0 and 102.0 <= lng <= 110.5


def http_get_json(url: str, headers: dict | None = None) -> dict | list:
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=45) as resp:
        return json.loads(resp.read().decode("utf-8"))


def geocode_nominatim(address: str) -> dict:
    """归一化为与 Google 类似的结构，便于统一处理。"""
    params = {
        "q": address,
        "format": "json",
        "addressdetails": 1,
        "limit": 1,
        "countrycodes": "vn",
    }
    url = NOMINATIM_URL + "?" + urllib.parse.urlencode(params)
    headers = {
        "User-Agent": USER_AGENT,
        "Accept-Language": "en",
    }
    try:
        data = http_get_json(url, headers)
    except urllib.error.HTTPError as e:
        return {"status": f"HTTP_{e.code}", "results": [], "error": str(e)}
    except Exception as e:
        return {"status": "HTTP_ERROR", "results": [], "error": str(e)}

    if not isinstance(data, list) or not data:
        return {"status": "ZERO_RESULTS", "results": [], "provider_raw": data}

    hit = data[0]
    addr = hit.get("address") or {}
    country_code = (addr.get("country_code") or "").upper()
    return {
        "status": "OK",
        "results": [{
            "formatted_address": hit.get("display_name"),
            "place_id": str(hit.get("place_id") or hit.get("osm_id") or ""),
            "geometry": {
                "location": {
                    "lat": float(hit["lat"]),
                    "lng": float(hit["lon"]),
                }
            },
            "address_components": [
                {"short_name": country_code, "types": ["country"]}
            ] if country_code else [],
            "_nominatim": {
                "osm_type": hit.get("osm_type"),
                "class": hit.get("class"),
                "type": hit.get("type"),
                "importance": hit.get("importance"),
            },
        }],
        "provider_raw": hit,
    }


def geocode_google(address: str, api_key: str) -> dict:
    params = {
        "address": address,
        "key": api_key,
        "region": "vn",
        "language": "en",
        "components": "country:VN",
    }
    url = GOOGLE_URL + "?" + urllib.parse.urlencode(params)
    try:
        payload = http_get_json(url, {"User-Agent": USER_AGENT})
    except Exception as e:
        return {"status": "HTTP_ERROR", "results": [], "error": str(e)}
    return payload


def country_ok_from_result(best: dict) -> bool:
    for comp in best.get("address_components") or []:
        if "country" in (comp.get("types") or []) and str(comp.get("short_name", "")).upper() == "VN":
            return True
    text = (best.get("formatted_address") or "").lower()
    return ("vietnam" in text) or ("việt" in text) or ("viet nam" in text)


def validate_one(row: dict, provider: str, api_key: str, max_distance_m: float, batch_id: str) -> dict:
    address = row.get("address_query") or row.get("name_en") or row.get("name")
    input_lat = row.get("latitude")
    input_lng = row.get("longitude")
    log = {
        "batch_id": batch_id,
        "provider": provider,
        "country_code": "VN",
        "source": row.get("source") or "CSC",
        "source_id": row.get("source_id"),
        "region_temp_id": row.get("id"),
        "level": row.get("level"),
        "input_name": row.get("name"),
        "input_address": address,
        "input_lat": input_lat,
        "input_lng": input_lng,
        # 字段名沿用 google_* 以兼容 geo_validation_log 表（存任意 geocoder 结果）
        "google_status": None,
        "google_formatted_address": None,
        "google_lat": None,
        "google_lng": None,
        "google_place_id": None,
        "distance_meters": None,
        "address_ok": 0,
        "latlng_ok": 0,
        "overall_ok": 0,
        "fail_reason": None,
        "raw_response": None,
    }

    if provider == "nominatim":
        payload = geocode_nominatim(address)
    else:
        payload = geocode_google(address, api_key)

    log["raw_response"] = json.dumps(payload, ensure_ascii=False)[:20000]
    status = payload.get("status")
    log["google_status"] = status
    results = payload.get("results") or []

    if status != "OK" or not results:
        log["fail_reason"] = f"geocode_status:{status}"
        if payload.get("error"):
            log["fail_reason"] += f":{payload['error']}"
        return log

    best = results[0]
    loc = best.get("geometry", {}).get("location", {})
    g_lat, g_lng = loc.get("lat"), loc.get("lng")
    log["google_formatted_address"] = best.get("formatted_address")
    log["google_lat"] = g_lat
    log["google_lng"] = g_lng
    log["google_place_id"] = best.get("place_id")

    if g_lat is None or g_lng is None:
        log["fail_reason"] = "missing_geocode_latlng"
        return log

    if in_vietnam_bounds(float(g_lat), float(g_lng)) and country_ok_from_result(best):
        log["address_ok"] = 1
    else:
        log["fail_reason"] = "address_not_in_vietnam"
        return log

    if input_lat is not None and input_lng is not None:
        dist = haversine_m(float(input_lat), float(input_lng), float(g_lat), float(g_lng))
        log["distance_meters"] = dist
        if dist is not None and dist <= max_distance_m:
            log["latlng_ok"] = 1
        else:
            log["fail_reason"] = f"latlng_distance_too_far:{dist:.1f}m"
            return log
    else:
        log["latlng_ok"] = 1
        row["latitude"] = g_lat
        row["longitude"] = g_lng
        row["_lat_filled_by"] = provider.upper()

    log["overall_ok"] = 1 if log["address_ok"] and log["latlng_ok"] else 0
    if log["overall_ok"]:
        row["_geocode_lat"] = g_lat
        row["_geocode_lng"] = g_lng
        row["_geocode_formatted_address"] = log["google_formatted_address"]
        row["_validated_by"] = provider
        row["_validated"] = True
    return log


def main():
    parser = argparse.ArgumentParser(description="VN geo validate (default: Nominatim free)")
    parser.add_argument("--provider", choices=["nominatim", "google"], default="nominatim")
    parser.add_argument("--api-key", default=os.environ.get("GOOGLE_MAPS_API_KEY", ""))
    parser.add_argument("--limit", type=int, default=0, help="0=全部")
    parser.add_argument(
        "--sleep",
        type=float,
        default=None,
        help="请求间隔秒；nominatim 默认 1.1，google 默认 0.25",
    )
    parser.add_argument("--max-distance-m", type=float, default=50000.0)
    parser.add_argument("--levels", default="1,2,3", help="校验层级，默认全部")
    args = parser.parse_args()

    if args.provider == "google" and not args.api_key:
        raise SystemExit("provider=google 时需要 GOOGLE_MAPS_API_KEY 或 --api-key")

    sleep_s = args.sleep
    if sleep_s is None:
        sleep_s = 1.1 if args.provider == "nominatim" else 0.25

    levels = {int(x) for x in args.levels.split(",") if x.strip()}
    batch_id = datetime.now(timezone.utc).strftime(f"VN_{args.provider.upper()}_%Y%m%d_%H%M%S")

    rows = []
    with (CLEAN / "vn_regions.jsonl").open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    rows = [r for r in rows if r.get("level") in levels]
    if args.limit and args.limit > 0:
        rows = rows[: args.limit]

    ok_path = VALIDATED / "vn_regions_ok.jsonl"
    fail_path = VALIDATED / "vn_regions_fail.jsonl"
    log_path = VALIDATED / "vn_validation_log.jsonl"

    ok_n = fail_n = 0
    print(f"provider={args.provider} sleep={sleep_s}s total={len(rows)} batch={batch_id}")
    with ok_path.open("w", encoding="utf-8") as okf, \
            fail_path.open("w", encoding="utf-8") as failf, \
            log_path.open("w", encoding="utf-8") as logf:
        for i, row in enumerate(rows, 1):
            print(f"[{i}/{len(rows)}] L{row.get('level')} {row.get('name_en') or row.get('name')}")
            log = validate_one(row, args.provider, args.api_key, args.max_distance_m, batch_id)
            logf.write(json.dumps(log, ensure_ascii=False) + "\n")
            if log.get("overall_ok") == 1:
                if row.get("latitude") is None and log.get("google_lat") is not None:
                    row["latitude"] = log["google_lat"]
                    row["longitude"] = log["google_lng"]
                okf.write(json.dumps(row, ensure_ascii=False) + "\n")
                ok_n += 1
            else:
                failf.write(json.dumps({"row": row, "log": log}, ensure_ascii=False) + "\n")
                fail_n += 1
                print(f"  FAIL: {log.get('fail_reason')}")
            time.sleep(sleep_s)

    summary = {
        "batch_id": batch_id,
        "provider": args.provider,
        "total": len(rows),
        "ok": ok_n,
        "fail": fail_n,
        "sleep": sleep_s,
        "max_distance_m": args.max_distance_m,
        "ok_file": str(ok_path),
        "fail_file": str(fail_path),
        "log_file": str(log_path),
    }
    (LOGS / "vn_validate_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

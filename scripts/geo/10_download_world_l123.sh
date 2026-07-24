#!/usr/bin/env bash
# 2026-07-24 GEO-001 下载全球 1~3 级原始数据（CSC）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
RAW="$ROOT/raw"
mkdir -p "$RAW"
cd "$RAW"

REL="https://github.com/dr5hn/countries-states-cities-database/releases/download/v3.2-export.6"

echo "[1/4] countries.json (master)"
curl -fsSL -o countries.json \
  "https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/json/countries.json"

echo "[2/4] states.json (master)"
curl -fsSL -o states.json \
  "https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/json/states.json"

echo "[3/4] json-cities.json.gz (release)"
curl -fsSL -L -o json-cities.json.gz "$REL/json-cities.json.gz"

echo "[4/4] gunzip cities → cities.json"
rm -f cities.json
gunzip -c json-cities.json.gz > cities.json

echo "DONE:"
ls -lh countries.json states.json cities.json json-cities.json.gz
python3 - <<'PY'
import json
from pathlib import Path
c=json.loads(Path('countries.json').read_text())
s=json.loads(Path('states.json').read_text())
# cities may be huge — count via ijson if available, else len
try:
    cities=json.loads(Path('cities.json').read_text())
    print(f"countries={len(c)} states={len(s)} cities={len(cities)}")
    print("city sample keys:", list(cities[0].keys())[:12])
except Exception as e:
    print("cities load check:", e)
PY

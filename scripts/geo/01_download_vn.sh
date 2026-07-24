#!/usr/bin/env bash
# 2026-07-24 GEO-001 拉取越南原始数据（CSC + GeoNames）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
RAW="$ROOT/raw"
mkdir -p "$RAW"
cd "$RAW"

echo "[1/3] CSC countries.json / states.json"
curl -fsSL -o countries.json \
  "https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/json/countries.json"
curl -fsSL -o states.json \
  "https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/json/states.json"

echo "[2/3] CSC contributions/cities/VN.json"
curl -fsSL -o cities_VN.json \
  "https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/contributions/cities/VN.json"

echo "[3/3] GeoNames VN.zip"
curl -fsSL -A "platform-geo-etl/1.0" -o VN.zip \
  "https://download.geonames.org/export/dump/VN.zip"
unzip -o VN.zip

echo "DONE. files:"
ls -lh countries.json states.json cities_VN.json VN.txt

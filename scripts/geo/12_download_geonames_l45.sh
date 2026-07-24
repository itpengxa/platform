#!/usr/bin/env bash
# GeoNames 下载：全量 → 按国（带重试）
# 城市级回退见：python3 12b_download_l4_by_city.py
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
RAW="$ROOT/raw/geonames"
mkdir -p "$RAW"
cd "$RAW"
UA="platform-geo-etl/1.0"
BASE="https://download.geonames.org/export/dump"

fetch() {
  local url="$1" out="$2"
  curl -fL --connect-timeout 30 --retry 3 --retry-delay 2 -A "$UA" -o "$out" "$url"
}

echo "[1] admin1CodesASCII.txt"
if [[ ! -s admin1CodesASCII.txt ]]; then
  if [[ -s /tmp/admin1Codes.txt ]]; then
    cp /tmp/admin1Codes.txt admin1CodesASCII.txt
  else
    fetch "$BASE/admin1CodesASCII.txt" admin1CodesASCII.txt || true
  fi
fi

echo "[2] allCountries.zip（一次性）"
if [[ -s allCountries.txt ]]; then
  echo "  already have allCountries.txt"
else
  if fetch "$BASE/allCountries.zip" allCountries.zip; then
    unzip -o allCountries.zip allCountries.txt
    rm -f allCountries.zip
    echo "  OK allCountries.txt"
    exit 0
  fi
  echo "  allCountries 失败，改为按国下载"
  rm -f allCountries.zip
fi

echo "[3] 按国下载 XX.zip"
CCS="${*:-}"
if [[ -z "$CCS" ]]; then
  CCS=$(mysql -N -uplatform -pplatform -h127.0.0.1 platform \
    -e "SELECT DISTINCT country_code FROM geo_region WHERE level=3 AND status=1
        AND country_code NOT IN ('CN','VN') ORDER BY 1" 2>/dev/null | tr '\n' ' ' || true)
fi

ok=0; fail=0; skip=0
for cc in $CCS; do
  cc=$(echo "$cc" | tr '[:lower:]' '[:upper:]')
  [[ "$cc" == "CN" || "$cc" == "VN" ]] && { skip=$((skip+1)); continue; }
  [[ -s "${cc}.txt" ]] && { skip=$((skip+1)); continue; }
  if fetch "$BASE/${cc}.zip" "${cc}.zip"; then
    unzip -qo "${cc}.zip" "${cc}.txt" 2>/dev/null || unzip -qo "${cc}.zip"
    rm -f "${cc}.zip"
    echo "  OK $cc ($(wc -l < "${cc}.txt" | tr -d ' ') lines)"
    ok=$((ok+1))
  else
    echo "  FAIL $cc"
    rm -f "${cc}.zip"
    fail=$((fail+1))
  fi
  sleep 0.6
done
echo "country dump done ok=$ok fail=$fail skip=$skip"
echo "若仍有缺口，跑城市级： python3 12b_download_l4_by_city.py --countries TH,US"

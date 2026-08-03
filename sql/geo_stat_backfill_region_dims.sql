-- 回填 platform_api_access_stat 的区划维度（从 request_params / api_key 解析）
-- 可重复执行：仅更新 region_id/country_code/region_level 仍为空的行

-- 1) parentId=123 / parentId="123"
UPDATE platform_api_access_stat
SET region_id = CAST(REGEXP_REPLACE(
        REGEXP_SUBSTR(request_params, 'parentId[=:]"?[0-9]+'),
        '[^0-9]', '') AS UNSIGNED)
WHERE region_id IS NULL
  AND request_params REGEXP 'parentId[=:]"?[0-9]+';

-- 2) rootId=
UPDATE platform_api_access_stat
SET region_id = CAST(REGEXP_REPLACE(
        REGEXP_SUBSTR(request_params, 'rootId[=:]"?[0-9]+'),
        '[^0-9]', '') AS UNSIGNED)
WHERE region_id IS NULL
  AND request_params REGEXP 'rootId[=:]"?[0-9]+';

-- 3) JSON "parentId":123
UPDATE platform_api_access_stat
SET region_id = CAST(REGEXP_REPLACE(
        REGEXP_SUBSTR(request_params, '"parentId"[[:space:]]*:[[:space:]]*[0-9]+'),
        '[^0-9]', '') AS UNSIGNED)
WHERE region_id IS NULL
  AND request_params REGEXP '"parentId"[[:space:]]*:[[:space:]]*[0-9]+';

-- 4) path API: GET /api/geo/v1/regions/{id}/path
UPDATE platform_api_access_stat
SET region_id = CAST(REGEXP_REPLACE(
        REGEXP_SUBSTR(api_key, '/regions/[0-9]+'),
        '[^0-9]', '') AS UNSIGNED)
WHERE region_id IS NULL
  AND api_key REGEXP '/regions/[0-9]+';

-- 5) countryCode=XX / countryCode="XX"（取末两位字母）
UPDATE platform_api_access_stat
SET country_code = UPPER(SUBSTRING(
        REGEXP_SUBSTR(request_params, 'countryCode[=:]"?[A-Za-z]{2}'), -2))
WHERE country_code IS NULL
  AND request_params REGEXP 'countryCode[=:]"?[A-Za-z]{2}';

-- 6) JSON "countryCode":"VN"
UPDATE platform_api_access_stat
SET country_code = UPPER(SUBSTRING(
        REGEXP_SUBSTR(request_params, '"countryCode"[[:space:]]*:[[:space:]]*"[A-Za-z]{2}"'), -2))
WHERE country_code IS NULL
  AND request_params REGEXP '"countryCode"[[:space:]]*:[[:space:]]*"[A-Za-z]{2}"';

-- 清理异常 0
UPDATE platform_api_access_stat SET region_id = NULL WHERE region_id = 0;

-- 7) 用主表补 country_code / region_level
UPDATE platform_api_access_stat s
INNER JOIN geo_region r ON r.id = s.region_id
SET s.country_code = COALESCE(s.country_code, r.country_code),
    s.region_level = COALESCE(s.region_level, r.level)
WHERE s.region_id IS NOT NULL
  AND (s.country_code IS NULL OR s.region_level IS NULL);

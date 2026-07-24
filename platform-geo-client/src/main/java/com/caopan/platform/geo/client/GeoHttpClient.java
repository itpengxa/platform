package com.caopan.platform.geo.client;

import com.caopan.platform.api.service.GeoService;
import com.caopan.platform.api.vo.CountryVO;
import com.caopan.platform.api.vo.RegionSearchVO;
import com.caopan.platform.api.vo.RegionTreeVO;
import com.caopan.platform.api.vo.RegionVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * GEO HTTP 客户端。封装对 geo 服务 REST API 的 HTTP 调用。
 * 使用 HttpURLConnection 零依赖，JDK 8 兼容。
 * 提供类型安全的 Java 方法，隐藏 HTTP 序列化/反序列化细节。
 */
public class GeoHttpClient implements GeoService {

    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public GeoHttpClient(String baseUrl) {
        this(baseUrl, new ObjectMapper());
    }

    public GeoHttpClient(String baseUrl, ObjectMapper objectMapper) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl required");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CountryVO> listCountries(String lang, String keyword) {
        StringBuilder path = new StringBuilder("/api/geo/v1/countries?");
        appendParam(path, "lang", lang);
        appendParam(path, "keyword", keyword);
        return getList(path.toString(), new TypeReference<List<CountryVO>>() {});
    }

    @Override
    public List<RegionVO> listChildren(Long parentId, String lang) {
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/children?");
        appendParam(path, "parentId", parentId == null ? null : String.valueOf(parentId));
        appendParam(path, "lang", lang);
        return getList(path.toString(), new TypeReference<List<RegionVO>>() {});
    }

    @Override
    public RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang) {
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/tree?");
        appendParam(path, "countryCode", countryCode);
        appendParam(path, "rootId", rootId == null ? null : String.valueOf(rootId));
        appendParam(path, "depth", depth == null ? null : String.valueOf(depth));
        appendParam(path, "lang", lang);
        return getData(path.toString(), new TypeReference<RegionTreeVO>() {});
    }

    @Override
    public List<RegionVO> getPath(Long id, String lang) {
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/");
        path.append(id).append("/path?");
        appendParam(path, "lang", lang);
        return getList(path.toString(), new TypeReference<List<RegionVO>>() {});
    }

    @Override
    public List<RegionSearchVO> search(String keyword, String countryCode, Integer level, Integer limit, String lang) {
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/search?");
        appendParam(path, "keyword", keyword);
        appendParam(path, "countryCode", countryCode);
        appendParam(path, "level", level == null ? null : String.valueOf(level));
        appendParam(path, "limit", limit == null ? null : String.valueOf(limit));
        appendParam(path, "lang", lang);
        return getList(path.toString(), new TypeReference<List<RegionSearchVO>>() {});
    }

    private <T> List<T> getList(String pathAndQuery, TypeReference<List<T>> type) {
        List<T> data = getData(pathAndQuery, type);
        return data == null ? Collections.<T>emptyList() : data;
    }

    private <T> T getData(String pathAndQuery, TypeReference<T> type) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + pathAndQuery);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                throw new GeoClientException("empty response, httpStatus=" + status);
            }
            JsonNode root = objectMapper.readTree(stream);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String message = root.path("message").asText("biz error");
                throw new GeoClientException("biz code=" + code + ", message=" + message);
            }
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                return null;
            }
            return objectMapper.convertValue(data, type);
        } catch (GeoClientException e) {
            throw e;
        } catch (IOException e) {
            throw new GeoClientException("http call failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.charAt(sb.length() - 1) != '?') {
            sb.append('&');
        }
        try {
            sb.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

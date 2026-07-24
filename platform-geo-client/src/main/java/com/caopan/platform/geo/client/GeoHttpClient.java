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
 * GEO HTTP 客户端（GEO-001 / platform-geo-client）。
 * <p>实现 {@link GeoService}，用 HttpURLConnection 调用 {@code /api/geo/v1/**}，
 * 零额外 HTTP 依赖；解析统一 {@code Result} 包装。网络/业务失败抛 {@link GeoClientException}。</p>
 */
public class GeoHttpClient implements GeoService {

    private final String baseUrl;
    private final ObjectMapper objectMapper;

    /**
     * 使用默认 ObjectMapper 构造。
     *
     * @param baseUrl 服务根地址，如 {@code http://localhost:8080}（勿带尾斜杠亦可）
     */
    public GeoHttpClient(String baseUrl) {
        this(baseUrl, new ObjectMapper());
    }

    /**
     * 注入依赖构造。
     *
     * @param baseUrl      服务根地址
     * @param objectMapper JSON 解析器
     */
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

    /**
     * 查询启用国家列表，支持语言与关键词过滤。
     *
     * @param lang    语言偏好（local/en/zh，可空）
     * @param keyword 关键词，可空
     * @return 国家列表
     */
    @Override
    public List<CountryVO> listCountries(String lang, String keyword) {
        StringBuilder path = new StringBuilder("/api/geo/v1/countries?");
        appendParam(path, "lang", lang);
        appendParam(path, "keyword", keyword);
        return getList(path.toString(), new TypeReference<List<CountryVO>>() {});
    }

    /**
     * 按父节点 ID 查询直属子行政区划列表。
     *
     * @param parentId 父节点 ID
     * @param lang     语言偏好（local/en/zh，可空）
     * @return 子级区划列表
     */
    @Override
    public List<RegionVO> listChildren(Long parentId, String lang) {
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/children?");
        appendParam(path, "parentId", parentId == null ? null : String.valueOf(parentId));
        appendParam(path, "lang", lang);
        return getList(path.toString(), new TypeReference<List<RegionVO>>() {});
    }

    /**
     * 按国家编码组装行政区划树。
     *
     * @param countryCode 国家 ISO2
     * @param rootId      根节点，可空
     * @param depth       深度，可空
     * @param lang        语言偏好，可空
     * @return 树根节点
     */
    @Override
    public RegionTreeVO getTree(String countryCode, Long rootId, Integer depth, String lang) {
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/tree?");
        appendParam(path, "countryCode", countryCode);
        appendParam(path, "rootId", rootId == null ? null : String.valueOf(rootId));
        appendParam(path, "depth", depth == null ? null : String.valueOf(depth));
        appendParam(path, "lang", lang);
        return getData(path.toString(), new TypeReference<RegionTreeVO>() {});
    }

    /**
     * 按区划 ID 回显祖先链。
     *
     * @param id   区划 ID（必填，&gt;0）
     * @param lang 语言偏好，可空
     * @return 祖先链
     */
    @Override
    public List<RegionVO> getPath(Long id, String lang) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("id required");
        }
        StringBuilder path = new StringBuilder("/api/geo/v1/regions/");
        path.append(id).append("/path?");
        appendParam(path, "lang", lang);
        return getList(path.toString(), new TypeReference<List<RegionVO>>() {});
    }

    /**
     * 按关键词搜索行政区划（须带 countryCode；服务端前缀匹配）。
     *
     * @param keyword     关键词
     * @param countryCode 国家 ISO2
     * @param level       层级过滤，可空
     * @param limit       条数上限，可空
     * @param lang        语言偏好，可空
     * @return 搜索结果列表
     */
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

    /**
     * GET 列表型 data，null 时返回空列表。
     */
    private <T> List<T> getList(String pathAndQuery, TypeReference<List<T>> type) {
        List<T> data = getData(pathAndQuery, type);
        return data == null ? Collections.<T>emptyList() : data;
    }

    /**
     * 发起 GET，解析 {@code Result}：code≠0 或 IO 失败抛 {@link GeoClientException}。
     */
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

    /**
     * 向查询串追加非空参数（URL 编码）。
     *
     * @param sb    已含 {@code ?} 的 path 缓冲
     * @param name  参数名
     * @param value 参数值，空则跳过
     */
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

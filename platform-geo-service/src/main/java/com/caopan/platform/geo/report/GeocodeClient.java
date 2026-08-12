package com.caopan.platform.geo.report;

import com.caopan.platform.geo.config.runtime.EffectiveReportSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 地图 Geocode 客户端（Nominatim / Google，GEO-002）。
 */
@Component
public class GeocodeClient {

    private static final Logger log = LoggerFactory.getLogger(GeocodeClient.class);

    private final EffectiveReportSettings properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeocodeClient(EffectiveReportSettings properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 查询地址坐标；无结果返回 empty。
     */
    public Optional<ParentBelongingChecker.GeocodeResult> geocode(String address) {
        if (!StringUtils.hasText(address)) {
            return Optional.empty();
        }
        try {
            return "google".equals(properties.normalizedProvider())
                    ? geocodeGoogle(address.trim())
                    : geocodeNominatim(address.trim());
        } catch (Exception e) {
            log.warn("geocode failed, provider={}, err={}", properties.normalizedProvider(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * 经纬度反查地址；无结果返回 empty。
     *
     * @param lat             纬度
     * @param lon             经度
     * @param acceptLanguage  可选，如 zh-CN / en；空则不传
     */
    public Optional<ReverseResult> reverse(double lat, double lon, String acceptLanguage) {
        try {
            return "google".equals(properties.normalizedProvider())
                    ? reverseGoogle(lat, lon, acceptLanguage)
                    : reverseNominatim(lat, lon, acceptLanguage);
        } catch (Exception e) {
            log.warn("reverse geocode failed, provider={}, lat={}, lon={}, err={}",
                    properties.normalizedProvider(), lat, lon, e.toString());
            return Optional.empty();
        }
    }

    /** 当前配置的 geocode 供应商名。 */
    public String currentProvider() {
        return properties.normalizedProvider();
    }

    private Optional<ReverseResult> reverseNominatim(double lat, double lon, String acceptLanguage) throws Exception {
        StringBuilder sb = new StringBuilder("https://nominatim.openstreetmap.org/reverse?lat=")
                .append(lat)
                .append("&lon=")
                .append(lon)
                .append("&format=json&addressdetails=1");
        if (StringUtils.hasText(acceptLanguage)) {
            sb.append("&accept-language=").append(URLEncoder.encode(acceptLanguage.trim(), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(sb.toString()))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", properties.nominatimUserAgent())
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || !StringUtils.hasText(resp.body())) {
            return Optional.empty();
        }
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.has("error") || !root.has("lat")) {
            return Optional.empty();
        }
        return Optional.of(parseNominatimReverse(root));
    }

    private ReverseResult parseNominatimReverse(JsonNode root) {
        double rLat = root.path("lat").asDouble();
        double rLon = root.path("lon").asDouble();
        String display = root.path("display_name").asText("");
        Map<String, String> parts = new LinkedHashMap<>();
        JsonNode addr = root.path("address");
        if (addr.isObject()) {
            addr.fields().forEachRemaining(e -> parts.put(e.getKey(), e.getValue().asText()));
        }
        List<String> bbox = new ArrayList<>();
        JsonNode bb = root.path("boundingbox");
        if (bb.isArray()) {
            bb.forEach(n -> bbox.add(n.asText()));
        }
        Long placeId = root.path("place_id").isMissingNode() || root.path("place_id").isNull()
                ? null : root.path("place_id").asLong();
        Long osmId = root.path("osm_id").isMissingNode() || root.path("osm_id").isNull()
                ? null : root.path("osm_id").asLong();
        Double importance = root.path("importance").isMissingNode() || root.path("importance").isNull()
                ? null : root.path("importance").asDouble();
        Integer placeRank = root.path("place_rank").isMissingNode() || root.path("place_rank").isNull()
                ? null : root.path("place_rank").asInt();
        return new ReverseResult(
                rLat, rLon, display, parts,
                placeId,
                root.path("osm_type").asText(null),
                osmId,
                root.path("class").asText(null),
                root.path("type").asText(null),
                root.path("addresstype").asText(null),
                root.path("name").asText(null),
                importance,
                placeRank,
                bbox,
                truncate(display, 1024));
    }

    private Optional<ReverseResult> reverseGoogle(double lat, double lon, String acceptLanguage) throws Exception {
        String key = properties.googleApiKey();
        if (!StringUtils.hasText(key)) {
            log.warn("google reverse selected but google-api-key empty");
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("https://maps.googleapis.com/maps/api/geocode/json?latlng=")
                .append(lat).append(',').append(lon)
                .append("&key=").append(key.trim());
        if (StringUtils.hasText(acceptLanguage)) {
            sb.append("&language=").append(URLEncoder.encode(acceptLanguage.trim(), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(sb.toString()))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || !StringUtils.hasText(resp.body())) {
            return Optional.empty();
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = results.get(0);
        JsonNode loc = first.path("geometry").path("location");
        double rLat = loc.path("lat").asDouble(lat);
        double rLon = loc.path("lng").asDouble(lon);
        String display = first.path("formatted_address").asText("");
        Map<String, String> parts = new LinkedHashMap<>();
        for (JsonNode comp : first.path("address_components")) {
            String longName = comp.path("long_name").asText();
            String shortName = comp.path("short_name").asText();
            for (JsonNode t : comp.path("types")) {
                String type = t.asText();
                parts.put(type, longName);
                if ("country".equals(type) && StringUtils.hasText(shortName)) {
                    parts.put("country_code", shortName.toLowerCase());
                }
            }
        }
        // 映射到接近 Nominatim 的常用键，便于统一向上匹配
        remapGoogleKeys(parts);
        List<String> bbox = new ArrayList<>();
        JsonNode vb = first.path("geometry").path("viewport");
        if (vb.isObject()) {
            bbox.add(vb.path("southwest").path("lat").asText(""));
            bbox.add(vb.path("northeast").path("lat").asText(""));
            bbox.add(vb.path("southwest").path("lng").asText(""));
            bbox.add(vb.path("northeast").path("lng").asText(""));
        }
        return Optional.of(new ReverseResult(
                rLat, rLon, display, parts,
                null, null, null,
                first.path("types").isArray() && !first.path("types").isEmpty()
                        ? first.path("types").get(0).asText(null) : null,
                first.path("types").isArray() && first.path("types").size() > 1
                        ? first.path("types").get(1).asText(null) : null,
                first.path("types").isArray() && !first.path("types").isEmpty()
                        ? first.path("types").get(0).asText(null) : null,
                null, null, null, bbox, truncate(display, 1024)));
    }

    private static void remapGoogleKeys(Map<String, String> parts) {
        putIfAbsent(parts, "road", firstOf(parts, "route", "street_address"));
        putIfAbsent(parts, "house_number", parts.get("street_number"));
        putIfAbsent(parts, "suburb", firstOf(parts, "sublocality", "sublocality_level_1", "neighborhood"));
        putIfAbsent(parts, "city", firstOf(parts, "locality", "postal_town", "administrative_area_level_2"));
        putIfAbsent(parts, "state", parts.get("administrative_area_level_1"));
        putIfAbsent(parts, "county", firstOf(parts, "administrative_area_level_2", "administrative_area_level_3"));
        putIfAbsent(parts, "postcode", parts.get("postal_code"));
    }

    private static void putIfAbsent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank() && !map.containsKey(key)) {
            map.put(key, value);
        }
    }

    private static String firstOf(Map<String, String> map, String... keys) {
        for (String k : keys) {
            String v = map.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Optional<ParentBelongingChecker.GeocodeResult> geocodeNominatim(String address) throws Exception {
        String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
        URI uri = URI.create("https://nominatim.openstreetmap.org/search?q="
                + encoded + "&format=json&addressdetails=1&limit=1");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", properties.nominatimUserAgent())
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || !StringUtils.hasText(resp.body())) {
            return Optional.empty();
        }
        JsonNode arr = objectMapper.readTree(resp.body());
        if (!arr.isArray() || arr.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = arr.get(0);
        double lat = first.path("lat").asDouble();
        double lng = first.path("lon").asDouble();
        String display = first.path("display_name").asText("");
        Map<String, String> parts = new LinkedHashMap<>();
        JsonNode addr = first.path("address");
        if (addr.isObject()) {
            addr.fields().forEachRemaining(e -> parts.put(e.getKey(), e.getValue().asText()));
        }
        String raw = truncate(display, 512);
        return Optional.of(new ParentBelongingChecker.GeocodeResult(lat, lng, display, parts, raw));
    }

    private Optional<ParentBelongingChecker.GeocodeResult> geocodeGoogle(String address) throws Exception {
        String key = properties.googleApiKey();
        if (!StringUtils.hasText(key)) {
            log.warn("google geocode selected but google-api-key empty");
            return Optional.empty();
        }
        String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
        URI uri = URI.create("https://maps.googleapis.com/maps/api/geocode/json?address="
                + encoded + "&key=" + key.trim());
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || !StringUtils.hasText(resp.body())) {
            return Optional.empty();
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = results.get(0);
        JsonNode loc = first.path("geometry").path("location");
        double lat = loc.path("lat").asDouble();
        double lng = loc.path("lng").asDouble();
        String display = first.path("formatted_address").asText("");
        Map<String, String> parts = new LinkedHashMap<>();
        for (JsonNode comp : first.path("address_components")) {
            String longName = comp.path("long_name").asText();
            for (JsonNode t : comp.path("types")) {
                parts.put(t.asText(), longName);
            }
        }
        return Optional.of(new ParentBelongingChecker.GeocodeResult(
                lat, lng, display, parts, truncate(display, 512)));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 反查原始结果（供应商无关的统一结构）。
     */
    public record ReverseResult(
            double lat,
            double lon,
            String displayName,
            Map<String, String> addressParts,
            Long placeId,
            String osmType,
            Long osmId,
            String category,
            String type,
            String addressType,
            String name,
            Double importance,
            Integer placeRank,
            List<String> boundingBox,
            String rawSummary) {
    }
}

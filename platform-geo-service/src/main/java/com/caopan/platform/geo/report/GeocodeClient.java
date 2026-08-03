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
import java.util.LinkedHashMap;
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
}

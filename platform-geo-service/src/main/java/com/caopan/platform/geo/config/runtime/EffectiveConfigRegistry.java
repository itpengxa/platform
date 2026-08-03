package com.caopan.platform.geo.config.runtime;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生效配置内存表（defaults ∪ DB overrides）。
 */
@Component
public class EffectiveConfigRegistry {

    private final AtomicReference<Map<String, String>> values =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final Set<String> overriddenKeys = ConcurrentHashMap.newKeySet();

    public void replaceAll(Map<String, String> effective, Set<String> overridden) {
        values.set(new ConcurrentHashMap<>(effective));
        overriddenKeys.clear();
        if (overridden != null) {
            overriddenKeys.addAll(overridden);
        }
    }

    public String get(String key) {
        return values.get().get(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        String v = get(key);
        return v != null ? v : defaultValue;
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = get(key);
        if (v == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v);
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(v.trim());
    }

    public long getLong(String key, long defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(v.trim());
    }

    public double getDouble(String key, double defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(v.trim());
    }

    public boolean isOverridden(String key) {
        return overriddenKeys.contains(key);
    }

    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(values.get());
    }
}

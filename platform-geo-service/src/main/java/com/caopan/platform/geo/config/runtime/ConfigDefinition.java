package com.caopan.platform.geo.config.runtime;

import java.util.List;

/**
 * 配置项元数据（驱动管理端表单与校验）。
 */
public record ConfigDefinition(
        String key,
        String group,
        ConfigValueType type,
        boolean secret,
        boolean hotReload,
        boolean writable,
        String description,
        Double min,
        Double max,
        List<String> enums
) {
    public static ConfigDefinition of(String key, String group, ConfigValueType type,
                                      boolean secret, boolean hotReload, boolean writable,
                                      String description) {
        return new ConfigDefinition(key, group, type, secret, hotReload, writable, description, null, null, null);
    }

    public static ConfigDefinition range(String key, String group, ConfigValueType type,
                                         boolean hotReload, String description, double min, double max) {
        return new ConfigDefinition(key, group, type, false, hotReload, true, description, min, max, null);
    }

    public static ConfigDefinition enums(String key, String group, boolean hotReload,
                                         String description, List<String> enums) {
        return new ConfigDefinition(key, group, ConfigValueType.STRING, false, hotReload, true,
                description, null, null, enums);
    }

    public static ConfigDefinition readonly(String key, String group, ConfigValueType type,
                                            boolean secret, String description) {
        return new ConfigDefinition(key, group, type, secret, false, false, description, null, null, null);
    }
}

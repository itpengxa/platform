package com.caopan.platform.geo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 访问日志 / 调用统计配置（JDK21 record + 构造器绑定）。
 */
@ConfigurationProperties(prefix = "platform.geo.access-log")
public record GeoAccessLogProperties(
        @DefaultValue("true") boolean argsEnabled,
        @DefaultValue("true") boolean exceptionEnabled,
        @DefaultValue("true") boolean statEnabled,
        @DefaultValue("2048") int paramsMaxLength
) {
    public int resolvedParamsMaxLength() {
        return Math.max(paramsMaxLength, 64);
    }
}

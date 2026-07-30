package com.caopan.platform.geo.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 物化路径解析工具（GEO-001 / platform-geo-service）。
 * <p>将 geo_region.path（如 {@code /1/200000001/300000010/}）拆成自根到叶的 ID 列表。</p>
 */
public final class PathUtil {

    private static final Logger log = LoggerFactory.getLogger(PathUtil.class);

    private PathUtil() {
    }

    /**
     * 按物化路径字符串解析祖先 ID 列表（如 {@code /1/200000001/300000010/}）。
     *
     * @param path 物化路径
     * @return 自根到叶的 ID 列表；非法片段会被跳过并记警告日志
     */
    public static List<Long> parsePathIds(String path) {
        if (!StringUtils.hasText(path)) {
            return List.of();
        }
        return Arrays.stream(path.split("/"))
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .mapMulti((String part, java.util.function.Consumer<Long> sink) -> {
                    try {
                        sink.accept(Long.parseLong(part));
                    } catch (NumberFormatException e) {
                        log.warn("skip invalid path segment, path={}, part={}", path, part);
                    }
                })
                .toList();
    }
}

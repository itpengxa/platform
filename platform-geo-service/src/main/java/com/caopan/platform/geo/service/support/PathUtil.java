package com.caopan.platform.geo.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 物化路径解析工具（GEO-001 / platform-geo-service）。
 * <p>将 geo_region.path（如 {@code /1/200000001/300000010/}）拆成自根到叶的 ID 列表，
 * 供祖先链回显与搜索 fullPathName 组装。非法数字片段跳过并记警告日志。</p>
 */
public final class PathUtil {

    private static final Logger log = LoggerFactory.getLogger(PathUtil.class);

    /** 工具类，禁止实例化 */
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
            return Collections.emptyList();
        }
        String[] parts = path.split("/");
        List<Long> ids = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                log.warn("skip invalid path segment, path={}, part={}", path, part);
            }
        }
        return ids;
    }
}

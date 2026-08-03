package com.caopan.platform.common.api;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果（GEO-002 管理端通用）。
 */
public record PageResult<T>(long total, int pageNum, int pageSize, List<T> list) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static <T> PageResult<T> of(long total, int pageNum, int pageSize, List<T> list) {
        return new PageResult<>(total, pageNum, pageSize, list == null ? Collections.emptyList() : list);
    }

    public long getTotal() {
        return total;
    }

    public int getPageNum() {
        return pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public List<T> getList() {
        return list;
    }
}

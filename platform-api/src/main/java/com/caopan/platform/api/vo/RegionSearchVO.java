package com.caopan.platform.api.vo;

/**
 * 区划搜索结果视图对象（GEO-001）。
 * <p>在 {@link RegionVO} 基础上增加 fullPathName：按 lang 拼接祖先链展示名，
 * 供搜索结果展示「国家/省/市/…」完整路径。search 强制国家 + 前缀匹配。</p>
 */
public class RegionSearchVO extends RegionVO {

    private static final long serialVersionUID = 1L;

    /** 全路径名称，用 / 连接各层 displayName */
    private String fullPathName;

    public String getFullPathName() {
        return fullPathName;
    }

    public void setFullPathName(String fullPathName) {
        this.fullPathName = fullPathName;
    }
}

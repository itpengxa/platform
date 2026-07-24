package com.caopan.platform.api.vo;

/**
 * 区划搜索结果视图对象。在 RegionVO 基础上增加 fullPathName 字段，
 * 按 lang 拼接完整的祖先链名称，用于搜索结果的路径展示。
 */
public class RegionSearchVO extends RegionVO {

    private static final long serialVersionUID = 1L;

    private String fullPathName;

    public String getFullPathName() {
        return fullPathName;
    }

    public void setFullPathName(String fullPathName) {
        this.fullPathName = fullPathName;
    }
}

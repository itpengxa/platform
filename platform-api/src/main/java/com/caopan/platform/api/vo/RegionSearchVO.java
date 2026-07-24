package com.caopan.platform.api.vo;

/**
 * 2026-07-24 GEO-001 搜索 VO
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

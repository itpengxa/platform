package com.caopan.platform.api.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-07-24 GEO-001 区划树 VO
 */
public class RegionTreeVO extends RegionVO {

    private static final long serialVersionUID = 1L;

    private List<RegionTreeVO> children = new ArrayList<RegionTreeVO>();

    public List<RegionTreeVO> getChildren() {
        return children;
    }

    public void setChildren(List<RegionTreeVO> children) {
        this.children = children;
    }
}

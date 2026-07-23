package com.caopan.platform.geo.vo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 2026-07-23 GEO-001 区划树 VO
 */
public class RegionTreeVO extends RegionVO {

    private List<RegionTreeVO> children = new ArrayList<>();

    public List<RegionTreeVO> getChildren() {
        return children;
    }

    public void setChildren(List<RegionTreeVO> children) {
        this.children = children;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RegionTreeVO that)) {
            return false;
        }
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }
}

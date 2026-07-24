package com.caopan.platform.api.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 区划视图对象（树形）。用于子树查询，包含嵌套的 children 子节点。
 * 递归结构，前端可直接渲染为可展开的树形组件。
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

package com.caopan.platform.api.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 区划树形视图对象（GEO-001）。
 * <p>继承 {@link RegionVO}，增加嵌套 children，供 {@code getTree} 接口直接渲染可展开树。
 * 深度由请求 depth 控制（通常 1~5），避免无界全量加载。</p>
 */
public class RegionTreeVO extends RegionVO {

    private static final long serialVersionUID = 1L;

    /** 子节点列表（递归） */
    private List<RegionTreeVO> children = new ArrayList<RegionTreeVO>();

    public List<RegionTreeVO> getChildren() {
        return children;
    }

    public void setChildren(List<RegionTreeVO> children) {
        this.children = children;
    }
}

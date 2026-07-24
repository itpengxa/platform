package com.caopan.platform.api.enums;

/**
 * 区划层级枚举（GEO-001）。
 * <p>数值 1~5 与 geo_region.level、ID 号段约定一致：
 * L1 国家约 1~250，L2 省州 2 亿+，L3 城市 3 亿+，L4 区县 4 亿+，L5 街镇 5 亿+。
 * 各国实际深度由数据决定，不写死必须到 5 级。</p>
 */
public enum RegionLevel {
    /** 国家（level=1） */
    COUNTRY(1),
    /** 省/州（level=2） */
    STATE(2),
    /** 城市（level=3） */
    CITY(3),
    /** 区/县（level=4） */
    DISTRICT(4),
    /** 街道/镇（level=5） */
    STREET(5);

    /** 层级数值：1~5 */
    private final int value;

    RegionLevel(int value) {
        this.value = value;
    }

    /**
     * @return 层级数值（1~5）
     */
    public int getValue() {
        return value;
    }
}

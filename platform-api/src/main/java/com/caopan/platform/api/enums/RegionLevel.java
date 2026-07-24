package com.caopan.platform.api.enums;

/**
 * 区划层级枚举。定义行政区划树的层级深度。
 * 1=国家, 2=省/州, 3=城市, 4=区/县, 5=街道/镇。
 * 各国深度不一时，由数据本身的 level 字段决定，不写死层级。
 */
public enum RegionLevel {
    COUNTRY(1),
    STATE(2),
    CITY(3),
    DISTRICT(4),
    STREET(5);

    private final int value;

    RegionLevel(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

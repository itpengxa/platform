package com.caopan.platform.api.enums;

/**
 * 2026-07-24 GEO-001 区划层级
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

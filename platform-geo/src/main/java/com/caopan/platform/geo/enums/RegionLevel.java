package com.caopan.platform.geo.enums;

/**
 * 2026-07-23 GEO-001 区划层级
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

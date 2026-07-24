package com.caopan.platform.api.enums;

/**
 * 区划类型枚举（GEO-001）。
 * <p>对应 geo_region.region_type，表达节点行政类型。
 * 不同国家命名习惯不同（如 PROVINCE / STATE），前端可据此做差异化展示。</p>
 */
public enum RegionType {
    /** 国家 */
    COUNTRY,
    /** 省（常见于中国等） */
    PROVINCE,
    /** 州（常见于美国等） */
    STATE,
    /** 城市 */
    CITY,
    /** 市辖区 */
    DISTRICT,
    /** 县 */
    COUNTY,
    /** 街道 */
    STREET,
    /** 镇 */
    TOWN
}

package com.caopan.platform.api.enums;

/**
 * 区划类型枚举。标识每个节点的具体行政类型。
 * 不同国家/地区可能有不同的命名习惯（如 PROVINCE / STATE），
 * 统一用 region_type 字段表达，便于前端按类型做差异化展示。
 */
public enum RegionType {
    COUNTRY,
    PROVINCE,
    STATE,
    CITY,
    DISTRICT,
    COUNTY,
    STREET,
    TOWN
}

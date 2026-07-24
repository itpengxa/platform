package com.caopan.platform.api.enums;

/**
 * 语言偏好枚举（GEO-001）。
 * <p>对应请求参数 lang：用于从 name / nameEn / nameCh 选取 displayName。
 * 非法或空值按 LOCAL（本地名）处理；亦兼容别名 ch→中文。</p>
 */
public enum LangType {
    /** 本地/缺省名称（name） */
    LOCAL,
    /** 英文名称（nameEn） */
    EN,
    /** 中文名称（nameCh） */
    ZH
}

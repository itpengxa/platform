package com.caopan.platform.api.enums;

/**
 * 语言类型枚举。定义支持的语言列表，每个枚举对应一种语言代码。
 * name = getName() 返回中划线格式（如 zh-CN），用于 DB 存储和展示。
 * code = getCode() 返回简码（如 zh），用于 properties 文件名。
 */
public enum LangType {
    LOCAL,
    EN,
    ZH
}

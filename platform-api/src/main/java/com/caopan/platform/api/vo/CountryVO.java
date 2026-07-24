package com.caopan.platform.api.vo;

import java.io.Serializable;

/**
 * 国家视图对象。返回给客户端的国家信息。
 * 列表接口默认不返回 iconBase64（体积大）；字段保留兼容，按需扩展图标接口。
 * displayName 按请求的 lang 参数从 name/nameEn/nameCh 中选取。
 */
public class CountryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    private Long id;
    /** ISO 3166-1 alpha-2 */
    private String iso2;
    /** ISO 3166-1 alpha-3 */
    private String iso3;
    /** 本地/缺省名称 */
    private String name;
    /** 英文名称 */
    private String nameEn;
    /** 中文名称 */
    private String nameCh;
    /** 按 lang 解析后的展示名 */
    private String displayName;
    /** 国旗图标 Base64（可空） */
    private String iconBase64;
    /** 国际电话区号 */
    private String phoneCode;
    /** 该国数据最大层级 */
    private Integer maxLevel;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIso2() { return iso2; }
    public void setIso2(String iso2) { this.iso2 = iso2; }
    public String getIso3() { return iso3; }
    public void setIso3(String iso3) { this.iso3 = iso3; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getNameCh() { return nameCh; }
    public void setNameCh(String nameCh) { this.nameCh = nameCh; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getIconBase64() { return iconBase64; }
    public void setIconBase64(String iconBase64) { this.iconBase64 = iconBase64; }
    public String getPhoneCode() { return phoneCode; }
    public void setPhoneCode(String phoneCode) { this.phoneCode = phoneCode; }
    public Integer getMaxLevel() { return maxLevel; }
    public void setMaxLevel(Integer maxLevel) { this.maxLevel = maxLevel; }
}

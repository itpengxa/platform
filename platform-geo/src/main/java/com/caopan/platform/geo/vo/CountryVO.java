package com.caopan.platform.geo.vo;

/**
 * 2026-07-23 GEO-001 国家 VO（含图标）
 */
public class CountryVO {

    private Long id;
    private String iso2;
    private String iso3;
    private String name;
    private String nameEn;
    private String nameCh;
    private String displayName;
    private String iconBase64;
    private String phoneCode;
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

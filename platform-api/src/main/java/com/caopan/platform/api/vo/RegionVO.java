package com.caopan.platform.api.vo;

import java.io.Serializable;

/**
 * 2026-07-24 GEO-001 区划 VO
 */
public class RegionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long parentId;
    private String countryCode;
    private String code;
    private String name;
    private String nameEn;
    private String nameCh;
    private String displayName;
    private Integer level;
    private String regionType;
    private String path;
    private Boolean isLeaf;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getNameCh() { return nameCh; }
    public void setNameCh(String nameCh) { this.nameCh = nameCh; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getRegionType() { return regionType; }
    public void setRegionType(String regionType) { this.regionType = regionType; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Boolean getIsLeaf() { return isLeaf; }
    public void setIsLeaf(Boolean isLeaf) { this.isLeaf = isLeaf; }
}

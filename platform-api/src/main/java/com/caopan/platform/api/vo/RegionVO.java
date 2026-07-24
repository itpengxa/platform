package com.caopan.platform.api.vo;

import java.io.Serializable;

/**
 * 区划视图对象（平级）。用于子级列表查询和祖先链回显。
 * isLeaf 标识是否末级节点，前端据此判断是否继续级联下钻。
 */
public class RegionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    private Long id;
    /** 父节点 ID，国家为 0 */
    private Long parentId;
    /** 所属国家 ISO2 编码 */
    private String countryCode;
    /** 行政编码 */
    private String code;
    /** 本地/缺省名称 */
    private String name;
    /** 英文名称 */
    private String nameEn;
    /** 中文名称 */
    private String nameCh;
    /** 按 lang 解析后的展示名 */
    private String displayName;
    /** 层级：1国家 2省州 3城市 4区县 5街镇 */
    private Integer level;
    /** 区划类型枚举字符串 */
    private String regionType;
    /** 物化路径，如 /1/200000001/300000010/ */
    private String path;
    /** 是否末级（无子节点） */
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

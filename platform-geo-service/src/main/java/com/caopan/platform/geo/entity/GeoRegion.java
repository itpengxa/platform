package com.caopan.platform.geo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 2026-07-23 GEO-001 行政区划树节点
 */
@TableName("geo_region")
/**
 * 行政区划树实体。与 geo_region 表对应。
 * 统一树模型：国家也是树节点（level=1），各国深度不一致由数据本身表达。
 * path 为物化路径（如 /240/3794/1570584/），一次查询取整条祖先链。
 */
public class GeoRegion {

    @TableId
    private Long id;
    private Long parentId;
    private String countryCode;
    private String name;
    private String nameEn;
    private String nameCh;
    private String code;
    private Integer level;
    private String regionType;
    private String path;
    private Integer isLeaf;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String source;
    private String sourceId;
    private Integer status;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getNameCh() { return nameCh; }
    public void setNameCh(String nameCh) { this.nameCh = nameCh; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getRegionType() { return regionType; }
    public void setRegionType(String regionType) { this.regionType = regionType; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Integer getIsLeaf() { return isLeaf; }
    public void setIsLeaf(Integer isLeaf) { this.isLeaf = isLeaf; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

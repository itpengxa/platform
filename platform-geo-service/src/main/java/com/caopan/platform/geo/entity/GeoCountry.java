package com.caopan.platform.geo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 国家扩展信息实体。与 geo_country 表对应。
 * 存储国家的 ISO 编码、多语言名称、图标、区号等信息。
 * max_level 标识该国数据的最深层级，告知调用方该国支持几级下钻。
 */
@TableName("geo_country")
public class GeoCountry {

    @TableId
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
    /** 国旗图标 Base64（可空） */
    private String iconBase64;
    /** 国际电话区号 */
    private String phoneCode;
    /** 货币代码 */
    private String currencyCode;
    /** 该国数据最大层级 */
    private Integer maxLevel;
    /** 状态：1启用 0停用 */
    private Integer status;
    /** 排序值 */
    private Integer sort;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;

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
    public String getIconBase64() { return iconBase64; }
    public void setIconBase64(String iconBase64) { this.iconBase64 = iconBase64; }
    public String getPhoneCode() { return phoneCode; }
    public void setPhoneCode(String phoneCode) { this.phoneCode = phoneCode; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public Integer getMaxLevel() { return maxLevel; }
    public void setMaxLevel(Integer maxLevel) { this.maxLevel = maxLevel; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

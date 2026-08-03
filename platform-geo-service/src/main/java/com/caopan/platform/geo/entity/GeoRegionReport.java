package com.caopan.platform.geo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 区划缺省上报记录（GEO-002）。
 */
@TableName("geo_region_report")
public class GeoRegionReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientCode;
    private Long parentId;
    private String countryCode;
    private String missingName;
    private String missingNameEn;
    private String missingNameCh;
    private String remark;
    private BigDecimal geocodeLat;
    private BigDecimal geocodeLng;
    private BigDecimal distanceKm;
    private String resultStatus;
    private Long regionId;
    private String geocodeRaw;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClientCode() { return clientCode; }
    public void setClientCode(String clientCode) { this.clientCode = clientCode; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getMissingName() { return missingName; }
    public void setMissingName(String missingName) { this.missingName = missingName; }
    public String getMissingNameEn() { return missingNameEn; }
    public void setMissingNameEn(String missingNameEn) { this.missingNameEn = missingNameEn; }
    public String getMissingNameCh() { return missingNameCh; }
    public void setMissingNameCh(String missingNameCh) { this.missingNameCh = missingNameCh; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public BigDecimal getGeocodeLat() { return geocodeLat; }
    public void setGeocodeLat(BigDecimal geocodeLat) { this.geocodeLat = geocodeLat; }
    public BigDecimal getGeocodeLng() { return geocodeLng; }
    public void setGeocodeLng(BigDecimal geocodeLng) { this.geocodeLng = geocodeLng; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public void setDistanceKm(BigDecimal distanceKm) { this.distanceKm = distanceKm; }
    public String getResultStatus() { return resultStatus; }
    public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
    public Long getRegionId() { return regionId; }
    public void setRegionId(Long regionId) { this.regionId = regionId; }
    public String getGeocodeRaw() { return geocodeRaw; }
    public void setGeocodeRaw(String geocodeRaw) { this.geocodeRaw = geocodeRaw; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

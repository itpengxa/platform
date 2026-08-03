package com.caopan.platform.geo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * API 调用记录实体（GEO-001 / platform-geo-service）。
 * <p>对应表 {@code platform_api_access_stat}。每请求一行，含应用、时间、接口、入参快照、成败；
 * 日调用量/成功率用 SQL {@code GROUP BY} 聚合，不另建明细表。</p>
 */
@TableName("platform_api_access_stat")
public class PlatformApiAccessStat {

    @TableId(type = IdType.AUTO)
    /** 主键 */
    private Long id;
    /** 调用来源 client_code；无身份时为 anonymous */
    private String clientCode;
    /** 调用时间（含毫秒） */
    private LocalDateTime calledAt;
    /** 如 {@code GET /api/geo/v1/regions/search} */
    private String apiKey;
    /** 入参 JSON/键值快照（截断） */
    private String requestParams;
    /** 1 成功 / 0 失败（抛异常含 BizException 为失败） */
    private Integer success;
    /** 失败时异常简名，成功可空 */
    private String errorType;
    /** 耗时毫秒 */
    private Integer costMs;
    /** 从入参解析的国家码（GEO-002） */
    private String countryCode;
    /** 被查节点/父节点 ID（GEO-002） */
    private Long regionId;
    /** 层级 1-5（GEO-002） */
    private Integer regionLevel;
    /** 落库时间 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClientCode() { return clientCode; }
    public void setClientCode(String clientCode) { this.clientCode = clientCode; }
    public LocalDateTime getCalledAt() { return calledAt; }
    public void setCalledAt(LocalDateTime calledAt) { this.calledAt = calledAt; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }
    public Integer getSuccess() { return success; }
    public void setSuccess(Integer success) { this.success = success; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public Integer getCostMs() { return costMs; }
    public void setCostMs(Integer costMs) { this.costMs = costMs; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public Long getRegionId() { return regionId; }
    public void setRegionId(Long regionId) { this.regionId = regionId; }
    public Integer getRegionLevel() { return regionLevel; }
    public void setRegionLevel(Integer regionLevel) { this.regionLevel = regionLevel; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

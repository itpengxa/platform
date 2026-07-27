package com.caopan.platform.geo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * API 接入方实体（GEO-001 / platform-geo-service）。
 * <p>对应表 {@code platform_access_client}。每个业务方（如 crm、trade）一条记录，
 * 通过 {@code client_code} 标识调用来源；停用后其下 Token 解析失败。</p>
 */
@TableName("platform_access_client")
public class PlatformAccessClient {

    @TableId(type = IdType.AUTO)
    /** 主键 */
    private Long id;
    /** 接入方编码（唯一），如 crm */
    private String clientCode;
    /** 展示名称 */
    private String clientName;
    /** 状态：1 启用 0 停用 */
    private Integer status;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClientCode() { return clientCode; }
    public void setClientCode(String clientCode) { this.clientCode = clientCode; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

package com.caopan.platform.api.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经纬度反查结果（Nominatim 风格精简结构）。
 * <p>优先本库近邻；无覆盖时回退地图供应商。字段命名对齐 Nominatim，便于前端复用。</p>
 */
public class ReverseGeocodeVO implements Serializable {

    private static final long serialVersionUID = 2L;

    /** 命中区划 ID（本库）或供应商 place_id */
    private Long id;
    /** 数据来源说明 */
    private String licence;
    /** 请求纬度（字符串，对齐 Nominatim） */
    private String lat;
    /** 请求经度 */
    private String lon;
    /** local / nominatim / … */
    private String clazz;
    private Integer placeRank;
    private Double importance;
    /** 如 STREET / WARD / PROVINCE */
    private String addresstype;
    private String name;
    private String displayName;
    /** 精简接口固定为 null（不回传供应商大对象） */
    private Object osm;
    /** 分层地址：road/county/state/country 及 *_code / *_type */
    private Map<String, String> address = new LinkedHashMap<>();
    private List<String> boundingbox = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLicence() { return licence; }
    public void setLicence(String licence) { this.licence = licence; }
    public String getLat() { return lat; }
    public void setLat(String lat) { this.lat = lat; }
    public String getLon() { return lon; }
    public void setLon(String lon) { this.lon = lon; }
    @JsonProperty("class")
    public String getClazz() { return clazz; }
    @JsonProperty("class")
    public void setClazz(String clazz) { this.clazz = clazz; }
    @JsonProperty("place_rank")
    public Integer getPlaceRank() { return placeRank; }
    @JsonProperty("place_rank")
    public void setPlaceRank(Integer placeRank) { this.placeRank = placeRank; }
    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }
    public String getAddresstype() { return addresstype; }
    public void setAddresstype(String addresstype) { this.addresstype = addresstype; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @JsonProperty("display_name")
    public String getDisplayName() { return displayName; }
    @JsonProperty("display_name")
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Object getOsm() { return osm; }
    public void setOsm(Object osm) { this.osm = osm; }
    public Map<String, String> getAddress() { return address; }
    public void setAddress(Map<String, String> address) { this.address = address; }
    public List<String> getBoundingbox() { return boundingbox; }
    public void setBoundingbox(List<String> boundingbox) { this.boundingbox = boundingbox; }
}

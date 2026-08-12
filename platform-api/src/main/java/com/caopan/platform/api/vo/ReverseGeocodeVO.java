package com.caopan.platform.api.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经纬度反查详细地址结果（GEO）。
 * <p>含地图供应商原始分层地址 + 本库「街道级向上」匹配结果。
 * OpenStreetMap / 第三方数据可能存在偏差，仅作参考，以本库 path 为准。</p>
 */
public class ReverseGeocodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求纬度 */
    private Double lat;
    /** 请求经度 */
    private Double lon;
    /** 供应商：nominatim / google */
    private String provider;
    /** OK / EMPTY / FAILED */
    private String providerStatus;
    /** 数据准确性说明 */
    private String providerNote;
    /** 供应商完整展示地址 */
    private String displayName;
    /** 供应商侧详情 */
    private OsmDetail osm;
    /** 本库匹配详情（可能为空） */
    private MatchDetail match;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLon() { return lon; }
    public void setLon(Double lon) { this.lon = lon; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public String getProviderNote() { return providerNote; }
    public void setProviderNote(String providerNote) { this.providerNote = providerNote; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public OsmDetail getOsm() { return osm; }
    public void setOsm(OsmDetail osm) { this.osm = osm; }
    public MatchDetail getMatch() { return match; }
    public void setMatch(MatchDetail match) { this.match = match; }

    /** 地图供应商原始详情。 */
    public static class OsmDetail implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long placeId;
        private String osmType;
        private Long osmId;
        private String category;
        private String type;
        private String addressType;
        private String name;
        private Double importance;
        private Integer placeRank;
        private List<String> boundingBox = new ArrayList<>();
        /** 原始 address 分层字段 */
        private Map<String, String> address = new LinkedHashMap<>();
        /** 归一化后的常用字段 */
        private NormalizedAddress normalized;

        public Long getPlaceId() { return placeId; }
        public void setPlaceId(Long placeId) { this.placeId = placeId; }
        public String getOsmType() { return osmType; }
        public void setOsmType(String osmType) { this.osmType = osmType; }
        public Long getOsmId() { return osmId; }
        public void setOsmId(Long osmId) { this.osmId = osmId; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getAddressType() { return addressType; }
        public void setAddressType(String addressType) { this.addressType = addressType; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Double getImportance() { return importance; }
        public void setImportance(Double importance) { this.importance = importance; }
        public Integer getPlaceRank() { return placeRank; }
        public void setPlaceRank(Integer placeRank) { this.placeRank = placeRank; }
        public List<String> getBoundingBox() { return boundingBox; }
        public void setBoundingBox(List<String> boundingBox) { this.boundingBox = boundingBox; }
        public Map<String, String> getAddress() { return address; }
        public void setAddress(Map<String, String> address) { this.address = address; }
        public NormalizedAddress getNormalized() { return normalized; }
        public void setNormalized(NormalizedAddress normalized) { this.normalized = normalized; }
    }

    /** 归一化地址字段，便于调用方直接使用。 */
    public static class NormalizedAddress implements Serializable {
        private static final long serialVersionUID = 1L;

        private String country;
        private String countryCode;
        private String state;
        private String city;
        private String district;
        private String suburb;
        private String county;
        private String street;
        private String houseNumber;
        private String postcode;

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getSuburb() { return suburb; }
        public void setSuburb(String suburb) { this.suburb = suburb; }
        public String getCounty() { return county; }
        public void setCounty(String county) { this.county = county; }
        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }
        public String getHouseNumber() { return houseNumber; }
        public void setHouseNumber(String houseNumber) { this.houseNumber = houseNumber; }
        public String getPostcode() { return postcode; }
        public void setPostcode(String postcode) { this.postcode = postcode; }
    }

    /** 本库匹配结果。 */
    public static class MatchDetail implements Serializable {
        private static final long serialVersionUID = 1L;

        /** DB_NEAREST：本库坐标近邻；OSM_STREET_UPWARD：Nominatim 名匹配兜底 */
        private String strategy = "DB_NEAREST";
        private Integer matchedLevel;
        private Long matchedRegionId;
        private String fullPathName;
        /** 命中点与请求坐标的距离（公里），本库近邻时有值 */
        private Double distanceKm;
        private List<RegionVO> path = new ArrayList<>();
        /** 每一级尝试轨迹 */
        private List<MatchTrial> trials = new ArrayList<>();

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public Integer getMatchedLevel() { return matchedLevel; }
        public void setMatchedLevel(Integer matchedLevel) { this.matchedLevel = matchedLevel; }
        public Long getMatchedRegionId() { return matchedRegionId; }
        public void setMatchedRegionId(Long matchedRegionId) { this.matchedRegionId = matchedRegionId; }
        public String getFullPathName() { return fullPathName; }
        public void setFullPathName(String fullPathName) { this.fullPathName = fullPathName; }
        public Double getDistanceKm() { return distanceKm; }
        public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
        public List<RegionVO> getPath() { return path; }
        public void setPath(List<RegionVO> path) { this.path = path; }
        public List<MatchTrial> getTrials() { return trials; }
        public void setTrials(List<MatchTrial> trials) { this.trials = trials; }
    }

    /** 单次层级匹配尝试。 */
    public static class MatchTrial implements Serializable {
        private static final long serialVersionUID = 1L;

        private Integer level;
        private String osmKey;
        private String osmValue;
        private String query;
        private Boolean hit;
        private Long regionId;
        private String regionName;
        private Double distanceKm;
        /** NO_CANDIDATE / NO_HIT / NEAREST / EXACT / CONTAINS / PREFIX / COUNTRY_CODE */
        private String reason;

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }
        public String getOsmKey() { return osmKey; }
        public void setOsmKey(String osmKey) { this.osmKey = osmKey; }
        public String getOsmValue() { return osmValue; }
        public void setOsmValue(String osmValue) { this.osmValue = osmValue; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public Boolean getHit() { return hit; }
        public void setHit(Boolean hit) { this.hit = hit; }
        public Long getRegionId() { return regionId; }
        public void setRegionId(Long regionId) { this.regionId = regionId; }
        public String getRegionName() { return regionName; }
        public void setRegionName(String regionName) { this.regionName = regionName; }
        public Double getDistanceKm() { return distanceKm; }
        public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}

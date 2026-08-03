package com.caopan.platform.geo.report;

/**
 * 上报结果状态常量（GEO-002）。
 */
public final class ReportResultStatus {

    public static final String AUTO_CREATED = "AUTO_CREATED";
    public static final String GEOCODE_FAIL = "GEOCODE_FAIL";
    public static final String DISTANCE_REJECT = "DISTANCE_REJECT";
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";
    public static final String PARENT_NO_COORD = "PARENT_NO_COORD";
    public static final String MANUAL_CREATED = "MANUAL_CREATED";
    public static final String REJECTED = "REJECTED";

    private ReportResultStatus() {
    }
}

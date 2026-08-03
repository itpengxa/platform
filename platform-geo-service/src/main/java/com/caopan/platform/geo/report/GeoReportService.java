package com.caopan.platform.geo.report;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.auth.CallerContext;
import com.caopan.platform.common.auth.CallerContextHolder;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.admin.support.RegionIdAllocator;
import com.caopan.platform.geo.config.runtime.EffectiveReportSettings;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.entity.GeoRegionReport;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.caopan.platform.geo.mapper.GeoRegionReportMapper;
import com.caopan.platform.geo.service.support.GeoDataCache;
import com.caopan.platform.geo.service.support.PathUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 缺省上报业务（GEO-002）：归属优先，距离兜底。
 */
@Service
public class GeoReportService {

    private final GeoRegionMapper geoRegionMapper;
    private final GeoRegionReportMapper reportMapper;
    private final GeocodeClient geocodeClient;
    private final RegionIdAllocator idAllocator;
    private final GeoDataCache geoDataCache;
    private final EffectiveReportSettings reportSettings;
    private final ReportRateLimiter rateLimiter;

    public GeoReportService(
            GeoRegionMapper geoRegionMapper,
            GeoRegionReportMapper reportMapper,
            GeocodeClient geocodeClient,
            RegionIdAllocator idAllocator,
            GeoDataCache geoDataCache,
            EffectiveReportSettings reportSettings,
            ReportRateLimiter rateLimiter) {
        this.geoRegionMapper = geoRegionMapper;
        this.reportMapper = reportMapper;
        this.geocodeClient = geocodeClient;
        this.idAllocator = idAllocator;
        this.geoDataCache = geoDataCache;
        this.reportSettings = reportSettings;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public ReportResponse reportMissing(ReportMissingRequest req) {
        CallerContext caller = CallerContextHolder.get();
        String clientCode = caller == null ? "anonymous" : caller.clientCode();
        assertRateLimit(clientCode);

        Long parentId = req.parentId();
        if (parentId == null || parentId <= 0 || !StringUtils.hasText(req.missingName())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }

        String missingName = req.missingName().trim();
        GeoRegionReport report = newBaseReport(clientCode, parent, req, missingName);
        LocalDateTime now = LocalDateTime.now();
        report.setCreatedAt(now);
        report.setUpdatedAt(now);

        // 同父下主表已有同名，或历史上已有上报记录，均视为已存在
        String nameEn = trimOptional(req.missingNameEn());
        if (geoRegionMapper.countSameNameUnderParent(parentId, missingName, nameEn, null) > 0
                || reportMapper.countByParentAndName(parentId, missingName) > 0) {
            report.setResultStatus(ReportResultStatus.ALREADY_EXISTS);
            reportMapper.insert(report);
            return toResponse(report, false, null, "Region already exists under parent");
        }

        String address = buildGeocodeAddress(parent, missingName);
        Optional<ParentBelongingChecker.GeocodeResult> geocodeOpt = geocodeClient.geocode(address);
        if (geocodeOpt.isEmpty()) {
            report.setResultStatus(ReportResultStatus.GEOCODE_FAIL);
            reportMapper.insert(report);
            return toResponse(report, false, null, "Geocode failed");
        }

        ParentBelongingChecker.GeocodeResult geocode = geocodeOpt.get();
        report.setGeocodeLat(BigDecimal.valueOf(geocode.lat()));
        report.setGeocodeLng(BigDecimal.valueOf(geocode.lng()));
        report.setGeocodeRaw(geocode.rawSummary());

        boolean underParent = ParentBelongingChecker.isUnderParent(parent, geocode);
        Double distanceKm = computeDistanceKm(parent, geocode);
        if (distanceKm != null) {
            report.setDistanceKm(GeoDistanceUtil.toDecimalKm(distanceKm));
        }

        if (underParent) {
            if (reportSettings.autoCreateEnabled()) {
                Long regionId = autoCreateRegion(parent, req, missingName, geocode, now);
                report.setRegionId(regionId);
                report.setResultStatus(ReportResultStatus.AUTO_CREATED);
            } else {
                report.setResultStatus(ReportResultStatus.DISTANCE_REJECT);
            }
        } else {
            if (parent.getLatitude() == null || parent.getLongitude() == null) {
                report.setResultStatus(ReportResultStatus.PARENT_NO_COORD);
            } else if (distanceKm == null || distanceKm > reportSettings.maxParentDistanceKm()) {
                report.setResultStatus(ReportResultStatus.DISTANCE_REJECT);
            } else if (reportSettings.autoCreateEnabled()) {
                Long regionId = autoCreateRegion(parent, req, missingName, geocode, now);
                report.setRegionId(regionId);
                report.setResultStatus(ReportResultStatus.AUTO_CREATED);
            } else {
                report.setResultStatus(ReportResultStatus.DISTANCE_REJECT);
            }
        }

        reportMapper.insert(report);
        return toResponse(report, underParent, distanceKm, null);
    }

    public PageResult<GeoRegionReport> pageAdmin(String resultStatus, String countryCode, String clientCode,
                                                 LocalDateTime from, LocalDateTime to,
                                                 int pageNum, int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        long total = reportMapper.countAdminPage(resultStatus, countryCode, clientCode, from, to);
        List<GeoRegionReport> rows = reportMapper.pageAdmin(resultStatus, countryCode, clientCode,
                from, to, (pn - 1) * ps, ps);
        return PageResult.of(total, pn, ps, rows);
    }

    @Transactional
    public void approve(Long id) {
        GeoRegionReport report = requireReport(id);
        if (ReportResultStatus.AUTO_CREATED.equals(report.getResultStatus())
                || ReportResultStatus.MANUAL_CREATED.equals(report.getResultStatus())) {
            return;
        }
        if (!ReportResultStatus.DISTANCE_REJECT.equals(report.getResultStatus())
                && !ReportResultStatus.PARENT_NO_COORD.equals(report.getResultStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        GeoRegion parent = geoRegionMapper.findEnabledById(report.getParentId());
        if (parent == null) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        ReportMissingRequest req = new ReportMissingRequest(
                report.getParentId(), report.getMissingName(),
                report.getMissingNameEn(), report.getMissingNameCh(), report.getRemark());
        ParentBelongingChecker.GeocodeResult geocode = null;
        if (report.getGeocodeLat() != null && report.getGeocodeLng() != null) {
            geocode = new ParentBelongingChecker.GeocodeResult(
                    report.getGeocodeLat().doubleValue(),
                    report.getGeocodeLng().doubleValue(),
                    report.getGeocodeRaw(), java.util.Map.of(), report.getGeocodeRaw());
        }
        Long regionId = autoCreateRegion(parent, req, report.getMissingName(), geocode, now);
        report.setRegionId(regionId);
        report.setResultStatus(ReportResultStatus.MANUAL_CREATED);
        report.setUpdatedAt(now);
        reportMapper.updateById(report);
    }

    @Transactional
    public void reject(Long id) {
        GeoRegionReport report = requireReport(id);
        report.setResultStatus(ReportResultStatus.REJECTED);
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
    }

    private void assertRateLimit(String clientCode) {
        if (!rateLimiter.tryAcquire(clientCode, reportSettings.rateLimitPerTokenPerHour())) {
            throw new BizException(ErrorCode.REPORT_RATE_LIMITED);
        }
    }

    private Long autoCreateRegion(GeoRegion parent, ReportMissingRequest req, String missingName,
                                  ParentBelongingChecker.GeocodeResult geocode, LocalDateTime now) {
        if (parent.getLevel() != null && parent.getLevel() >= 5) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        int level = parent.getLevel() + 1;
        long newId = idAllocator.allocate(level);
        String path = parent.getPath() + newId + "/";

        GeoRegion row = new GeoRegion();
        row.setId(newId);
        row.setParentId(parent.getId());
        row.setCountryCode(parent.getCountryCode());
        row.setName(missingName);
        row.setNameEn(trimOptional(req.missingNameEn()));
        row.setNameCh(trimOptional(req.missingNameCh()));
        row.setLevel(level);
        row.setRegionType(switch (level) {
            case 1 -> "country";
            case 2 -> "province";
            case 3 -> "city";
            case 4 -> "district";
            case 5 -> "street";
            default -> "region";
        });
        row.setPath(path);
        row.setIsLeaf(1);
        row.setSource("user_report");
        row.setStatus(1);
        row.setSort(0);
        if (geocode != null) {
            row.setLatitude(BigDecimal.valueOf(geocode.lat()));
            row.setLongitude(BigDecimal.valueOf(geocode.lng()));
        }
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        geoRegionMapper.insert(row);

        parent.setIsLeaf(0);
        parent.setUpdatedAt(now);
        geoRegionMapper.updateById(parent);
        geoDataCache.evictAfterMutation(newId, parent.getId(), parent.getCountryCode());
        return newId;
    }

    private static GeoRegionReport newBaseReport(String clientCode, GeoRegion parent,
                                                 ReportMissingRequest req, String missingName) {
        GeoRegionReport report = new GeoRegionReport();
        report.setClientCode(clientCode);
        report.setParentId(parent.getId());
        report.setCountryCode(parent.getCountryCode());
        report.setMissingName(missingName);
        report.setMissingNameEn(trimOptional(req.missingNameEn()));
        report.setMissingNameCh(trimOptional(req.missingNameCh()));
        report.setRemark(trimOptional(req.remark()));
        return report;
    }

    private String buildGeocodeAddress(GeoRegion parent, String missingName) {
        List<Long> ids = PathUtil.parsePathIds(parent.getPath());
        List<String> parts = new ArrayList<>();
        if (!ids.isEmpty()) {
            List<GeoRegion> chain = geoRegionMapper.listByIds(ids);
            for (GeoRegion r : chain) {
                if (StringUtils.hasText(r.getName())) {
                    parts.add(r.getName());
                }
            }
        }
        parts.add(missingName);
        if (StringUtils.hasText(parent.getCountryCode())) {
            parts.add(parent.getCountryCode());
        }
        return String.join(", ", parts);
    }

    private static Double computeDistanceKm(GeoRegion parent, ParentBelongingChecker.GeocodeResult geocode) {
        if (parent.getLatitude() == null || parent.getLongitude() == null) {
            return null;
        }
        return GeoDistanceUtil.distanceKm(
                parent.getLatitude().doubleValue(),
                parent.getLongitude().doubleValue(),
                geocode.lat(),
                geocode.lng());
    }

    private GeoRegionReport requireReport(Long id) {
        GeoRegionReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return report;
    }

    private static ReportResponse toResponse(GeoRegionReport report, Boolean underParent,
                                             Double distanceKm, String message) {
        // 避免三元 true 分支为 primitive double、false 为 Double 时自动拆箱 NPE
        Double resolvedDistance = distanceKm;
        if (resolvedDistance == null && report.getDistanceKm() != null) {
            resolvedDistance = report.getDistanceKm().doubleValue();
        }
        return new ReportResponse(
                report.getId(),
                report.getResultStatus(),
                report.getRegionId(),
                underParent,
                resolvedDistance,
                message);
    }

    private static String trimOptional(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    public record ReportMissingRequest(
            Long parentId, String missingName, String missingNameEn, String missingNameCh, String remark) {
    }

    public record ReportResponse(
            Long reportId, String resultStatus, Long regionId,
            Boolean underParent, Double distanceKm, String message) {
    }
}

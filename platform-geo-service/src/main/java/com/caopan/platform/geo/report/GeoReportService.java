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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 缺省上报业务（GEO-002）：归属优先，距离兜底。
 * <p>外部 geocode 在事务外执行；短事务仅覆盖重复校验 / 号段分配 / 落库，避免拖死连接池。</p>
 */
@Service
public class GeoReportService {

    private static final List<String> REVIEWABLE = List.of(
            ReportResultStatus.DISTANCE_REJECT,
            ReportResultStatus.PARENT_NO_COORD);

    private final GeoRegionMapper geoRegionMapper;
    private final GeoRegionReportMapper reportMapper;
    private final GeocodeClient geocodeClient;
    private final RegionIdAllocator idAllocator;
    private final GeoDataCache geoDataCache;
    private final EffectiveReportSettings reportSettings;
    private final ReportRateLimiter rateLimiter;
    private final TransactionTemplate transactionTemplate;

    public GeoReportService(
            GeoRegionMapper geoRegionMapper,
            GeoRegionReportMapper reportMapper,
            GeocodeClient geocodeClient,
            RegionIdAllocator idAllocator,
            GeoDataCache geoDataCache,
            EffectiveReportSettings reportSettings,
            ReportRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager) {
        this.geoRegionMapper = geoRegionMapper;
        this.reportMapper = reportMapper;
        this.geocodeClient = geocodeClient;
        this.idAllocator = idAllocator;
        this.geoDataCache = geoDataCache;
        this.reportSettings = reportSettings;
        this.rateLimiter = rateLimiter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ReportResponse reportMissing(ReportMissingRequest req) {
        CallerContext caller = CallerContextHolder.get();
        String clientCode = caller == null ? "anonymous" : caller.clientCode();
        assertRateLimit(clientCode);

        Long parentId = req.parentId();
        if (parentId == null || parentId <= 0 || !StringUtils.hasText(req.missingName())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        // 读校验在事务外：不占用连接等待外部 HTTP
        GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }

        String missingName = req.missingName().trim();
        String nameEn = trimOptional(req.missingNameEn());
        GeoRegionReport report = newBaseReport(clientCode, parent, req, missingName);

        if (geoRegionMapper.countSameNameUnderParent(parentId, missingName, nameEn, null) > 0
                || reportMapper.countByParentAndName(parentId, missingName) > 0) {
            report.setResultStatus(ReportResultStatus.ALREADY_EXISTS);
            return persistReport(report, false, null, "Region already exists under parent",
                    false, parent, req, missingName, null);
        }

        String address = buildGeocodeAddress(parent, missingName);
        Optional<ParentBelongingChecker.GeocodeResult> geocodeOpt = geocodeClient.geocode(address);
        if (geocodeOpt.isEmpty()) {
            report.setResultStatus(ReportResultStatus.GEOCODE_FAIL);
            return persistReport(report, false, null, "Geocode failed",
                    false, parent, req, missingName, null);
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

        boolean needCreate = false;
        if (underParent) {
            if (reportSettings.autoCreateEnabled()) {
                report.setResultStatus(ReportResultStatus.AUTO_CREATED);
                needCreate = true;
            } else {
                report.setResultStatus(ReportResultStatus.DISTANCE_REJECT);
            }
        } else if (parent.getLatitude() == null || parent.getLongitude() == null) {
            report.setResultStatus(ReportResultStatus.PARENT_NO_COORD);
        } else if (distanceKm == null || distanceKm > reportSettings.maxParentDistanceKm()) {
            report.setResultStatus(ReportResultStatus.DISTANCE_REJECT);
        } else if (reportSettings.autoCreateEnabled()) {
            report.setResultStatus(ReportResultStatus.AUTO_CREATED);
            needCreate = true;
        } else {
            report.setResultStatus(ReportResultStatus.DISTANCE_REJECT);
        }

        return persistReport(report, underParent, distanceKm, null,
                needCreate, parent, req, missingName, geocode);
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
        GeoRegionReport report = reportMapper.selectForUpdate(id);
        if (report == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (ReportResultStatus.AUTO_CREATED.equals(report.getResultStatus())
                || ReportResultStatus.MANUAL_CREATED.equals(report.getResultStatus())) {
            return;
        }
        if (!REVIEWABLE.contains(report.getResultStatus())) {
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
        int n = reportMapper.updateStatusIf(
                id, REVIEWABLE, ReportResultStatus.MANUAL_CREATED, regionId, now);
        if (n == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    @Transactional
    public void reject(Long id) {
        GeoRegionReport report = reportMapper.selectForUpdate(id);
        if (report == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (!REVIEWABLE.contains(report.getResultStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        int n = reportMapper.updateStatusIf(
                id, REVIEWABLE, ReportResultStatus.REJECTED, null, LocalDateTime.now());
        if (n == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    /**
     * 短事务落库：事务内再校验同名，必要时自动建区 + insert 上报。
     */
    private ReportResponse persistReport(
            GeoRegionReport report,
            Boolean underParent,
            Double distanceKm,
            String message,
            boolean needCreate,
            GeoRegion parent,
            ReportMissingRequest req,
            String missingName,
            ParentBelongingChecker.GeocodeResult geocode) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            report.setCreatedAt(now);
            report.setUpdatedAt(now);

            String nameEn = trimOptional(req.missingNameEn());
            if (!ReportResultStatus.ALREADY_EXISTS.equals(report.getResultStatus())
                    && (geoRegionMapper.countSameNameUnderParent(parent.getId(), missingName, nameEn, null) > 0
                    || reportMapper.countByParentAndName(parent.getId(), missingName) > 0)) {
                report.setResultStatus(ReportResultStatus.ALREADY_EXISTS);
                report.setRegionId(null);
                reportMapper.insert(report);
                return toResponse(report, underParent, distanceKm, "Region already exists under parent");
            }

            if (needCreate && ReportResultStatus.AUTO_CREATED.equals(report.getResultStatus())) {
                Long regionId = autoCreateRegion(parent, req, missingName, geocode, now);
                report.setRegionId(regionId);
            }

            reportMapper.insert(report);
            return toResponse(report, underParent, distanceKm, message);
        });
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
        DuplicateKeyException lastDup = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long newId = idAllocator.allocate(level);
            try {
                return insertNewRegion(parent, req, missingName, geocode, now, newId, level);
            } catch (DuplicateKeyException e) {
                lastDup = e;
            }
        }
        throw lastDup != null ? lastDup : new BizException(ErrorCode.SYSTEM_ERROR);
    }

    private Long insertNewRegion(GeoRegion parent, ReportMissingRequest req, String missingName,
                                 ParentBelongingChecker.GeocodeResult geocode, LocalDateTime now,
                                 long newId, int level) {
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

    private static ReportResponse toResponse(GeoRegionReport report, Boolean underParent,
                                             Double distanceKm, String message) {
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

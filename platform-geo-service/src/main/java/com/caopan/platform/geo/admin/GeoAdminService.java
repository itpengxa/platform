package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.admin.support.RegionIdAllocator;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.caopan.platform.geo.service.support.GeoDataCache;
import com.caopan.platform.geo.service.support.PathUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 区划主表管理（GEO-002）。
 */
@Service
public class GeoAdminService {

    private final GeoRegionMapper geoRegionMapper;
    private final RegionIdAllocator idAllocator;
    private final GeoDataCache geoDataCache;

    public GeoAdminService(
            GeoRegionMapper geoRegionMapper,
            RegionIdAllocator idAllocator,
            GeoDataCache geoDataCache) {
        this.geoRegionMapper = geoRegionMapper;
        this.idAllocator = idAllocator;
        this.geoDataCache = geoDataCache;
    }

    public PageResult<GeoRegionAdminVO> page(String countryCode, Long parentId, Integer level,
                                             String keyword, Integer status, String source,
                                             int pageNum, int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        String cc = normalizeCountry(countryCode);
        String kw = normalizeKeyword(keyword);
        String src = StringUtils.hasText(source) ? source.trim() : null;
        long total = geoRegionMapper.countAdminPage(cc, parentId, level, kw, status, src);
        List<GeoRegion> rows = geoRegionMapper.pageAdmin(cc, parentId, level, kw, status, src,
                (pn - 1) * ps, ps);
        return PageResult.of(total, pn, ps, rows.stream().map(this::toVo).toList());
    }

    public GeoRegionAdminVO detail(Long id) {
        GeoRegion region = requireRegion(id);
        GeoRegionAdminVO vo = toVo(region);
        vo = new GeoRegionAdminVO(
                vo.id(), vo.parentId(), vo.countryCode(), vo.name(), vo.nameEn(), vo.nameCh(),
                vo.code(), vo.level(), vo.path(), vo.isLeaf(), vo.latitude(), vo.longitude(),
                vo.status(), vo.sort(), vo.source(), vo.createdAt(), vo.updatedAt(),
                buildPathNames(region));
        return vo;
    }

    @Transactional
    public GeoRegionAdminVO create(GeoRegionCreateRequest req) {
        GeoRegion parent = requireEnabledParent(req.parentId());
        if (parent.getLevel() != null && parent.getLevel() >= 5) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        int level = parent.getLevel() + 1;
        String name = trimRequired(req.name(), "name");
        assertNoDuplicate(req.parentId(), name, trimOptional(req.nameEn()), null);

        long newId = idAllocator.allocate(level);
        String path = parent.getPath() + newId + "/";
        LocalDateTime now = LocalDateTime.now();

        GeoRegion row = new GeoRegion();
        row.setId(newId);
        row.setParentId(req.parentId());
        row.setCountryCode(parent.getCountryCode());
        row.setName(name);
        row.setNameEn(trimOptional(req.nameEn()));
        row.setNameCh(trimOptional(req.nameCh()));
        row.setCode(trimOptional(req.code()));
        row.setLevel(level);
        row.setRegionType(levelLabel(level));
        row.setPath(path);
        row.setIsLeaf(1);
        row.setLatitude(req.latitude());
        row.setLongitude(req.longitude());
        row.setSource("admin");
        row.setStatus(req.status() == null ? 1 : req.status());
        row.setSort(req.sort() == null ? 0 : req.sort());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        geoRegionMapper.insert(row);

        parent.setIsLeaf(0);
        parent.setUpdatedAt(now);
        geoRegionMapper.updateById(parent);

        geoDataCache.evictAfterMutation(newId, req.parentId(), parent.getCountryCode());
        return toVo(row);
    }

    @Transactional
    public void update(Long id, GeoRegionUpdateRequest req) {
        GeoRegion row = requireRegion(id);
        Long parentId = row.getParentId();
        String countryCode = row.getCountryCode();

        if (StringUtils.hasText(req.name())) {
            String name = req.name().trim();
            assertNoDuplicate(parentId, name, trimOptional(req.nameEn()), id);
            row.setName(name);
        }
        if (req.nameEn() != null) {
            row.setNameEn(trimOptional(req.nameEn()));
        }
        if (req.nameCh() != null) {
            row.setNameCh(trimOptional(req.nameCh()));
        }
        if (req.code() != null) {
            row.setCode(trimOptional(req.code()));
        }
        if (req.latitude() != null) {
            row.setLatitude(req.latitude());
        }
        if (req.longitude() != null) {
            row.setLongitude(req.longitude());
        }
        if (req.sort() != null) {
            row.setSort(req.sort());
        }
        if (req.status() != null) {
            row.setStatus(req.status());
        }
        row.setUpdatedAt(LocalDateTime.now());
        geoRegionMapper.updateById(row);
        geoDataCache.evictAfterMutation(id, parentId, countryCode);
    }

    @Transactional
    public void patchStatus(Long id, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        GeoRegion row = requireRegion(id);
        row.setStatus(status);
        row.setUpdatedAt(LocalDateTime.now());
        geoRegionMapper.updateById(row);
        geoDataCache.evictAfterMutation(id, row.getParentId(), row.getCountryCode());
    }

    private GeoRegion requireRegion(Long id) {
        GeoRegion row = geoRegionMapper.findByIdAnyStatus(id);
        if (row == null) {
            throw new BizException(ErrorCode.REGION_NOT_FOUND);
        }
        return row;
    }

    private GeoRegion requireEnabledParent(Long parentId) {
        if (parentId == null || parentId <= 0) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        return parent;
    }

    private void assertNoDuplicate(Long parentId, String name, String nameEn, Long excludeId) {
        long cnt = geoRegionMapper.countSameNameUnderParent(parentId, name, nameEn, excludeId);
        if (cnt > 0) {
            throw new BizException(ErrorCode.REGION_DUPLICATE);
        }
    }

    private String buildPathNames(GeoRegion region) {
        List<Long> ids = PathUtil.parsePathIds(region.getPath());
        if (ids.isEmpty()) {
            return region.getName();
        }
        List<GeoRegion> chain = geoRegionMapper.listByIds(ids);
        Map<Long, String> nameMap = chain.stream()
                .collect(Collectors.toMap(GeoRegion::getId, GeoRegion::getName, (a, b) -> a));
        List<String> names = new ArrayList<>();
        for (Long pid : ids) {
            String n = nameMap.get(pid);
            if (StringUtils.hasText(n)) {
                names.add(n);
            }
        }
        return String.join(" / ", names);
    }

    private GeoRegionAdminVO toVo(GeoRegion r) {
        return new GeoRegionAdminVO(
                r.getId(), r.getParentId(), r.getCountryCode(),
                r.getName(), r.getNameEn(), r.getNameCh(), r.getCode(),
                r.getLevel(), r.getPath(), r.getIsLeaf(),
                r.getLatitude(), r.getLongitude(),
                r.getStatus(), r.getSort(), r.getSource(),
                r.getCreatedAt(), r.getUpdatedAt(), null);
    }

    private static String normalizeCountry(String countryCode) {
        if (!StringUtils.hasText(countryCode)) {
            return null;
        }
        return countryCode.trim().toUpperCase();
    }

    private static String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String kw = keyword.trim();
        if (kw.contains("%") || kw.contains("_")) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return kw;
    }

    private static String trimRequired(String s, String field) {
        if (!StringUtils.hasText(s)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return s.trim();
    }

    private static String trimOptional(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String levelLabel(int level) {
        return switch (level) {
            case 1 -> "country";
            case 2 -> "province";
            case 3 -> "city";
            case 4 -> "district";
            case 5 -> "street";
            default -> "region";
        };
    }

    public record GeoRegionAdminVO(
            Long id, Long parentId, String countryCode,
            String name, String nameEn, String nameCh, String code,
            Integer level, String path, Integer isLeaf,
            BigDecimal latitude, BigDecimal longitude,
            Integer status, Integer sort, String source,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            String fullPathName) {
    }

    public record GeoRegionCreateRequest(
            Long parentId, String name, String nameEn, String nameCh, String code,
            BigDecimal latitude, BigDecimal longitude, Integer sort, Integer status) {
    }

    public record GeoRegionUpdateRequest(
            String name, String nameEn, String nameCh, String code,
            BigDecimal latitude, BigDecimal longitude, Integer sort, Integer status) {
    }
}

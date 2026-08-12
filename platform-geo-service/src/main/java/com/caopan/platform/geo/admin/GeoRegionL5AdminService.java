package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.admin.support.RegionIdAllocator;
import com.caopan.platform.geo.entity.GeoRegion;
import com.caopan.platform.geo.entity.GeoRegionL5;
import com.caopan.platform.geo.mapper.GeoRegionL5Mapper;
import com.caopan.platform.geo.mapper.GeoRegionMapper;
import com.caopan.platform.geo.service.support.GeoDataCache;
import com.caopan.platform.geo.service.support.PathUtil;
import org.springframework.dao.DuplicateKeyException;
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
 * L5 街/镇管理（表 geo_region_l5）。
 * <p>父节点必须是主表 {@code geo_region} 的 L4；街道级数据不入主表。</p>
 */
@Service
public class GeoRegionL5AdminService {

    private final GeoRegionL5Mapper geoRegionL5Mapper;
    private final GeoRegionMapper geoRegionMapper;
    private final RegionIdAllocator idAllocator;
    private final GeoDataCache geoDataCache;

    public GeoRegionL5AdminService(
            GeoRegionL5Mapper geoRegionL5Mapper,
            GeoRegionMapper geoRegionMapper,
            RegionIdAllocator idAllocator,
            GeoDataCache geoDataCache) {
        this.geoRegionL5Mapper = geoRegionL5Mapper;
        this.geoRegionMapper = geoRegionMapper;
        this.idAllocator = idAllocator;
        this.geoDataCache = geoDataCache;
    }

    public PageResult<GeoRegionL5AdminVO> page(String countryCode, Long parentId,
                                               String keyword, Integer status, String source,
                                               int pageNum, int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        String cc = normalizeCountry(countryCode);
        String kw = normalizeKeyword(keyword);
        String src = StringUtils.hasText(source) ? source.trim() : null;
        boolean noFilter = !StringUtils.hasText(cc) && parentId == null
                && !StringUtils.hasText(kw) && status == null && !StringUtils.hasText(src);
        long total = geoRegionL5Mapper.countAdminPage(cc, parentId, kw, status, src);
        int offset = (pn - 1) * ps;
        if (noFilter && offset > 20000) {
            pn = 20000 / ps + 1;
            offset = (pn - 1) * ps;
        }
        List<GeoRegionL5> rows = geoRegionL5Mapper.pageAdmin(cc, parentId, kw, status, src, offset, ps);
        return PageResult.of(total, pn, ps, rows.stream().map(this::toVo).toList());
    }

    public GeoRegionL5AdminVO detail(Long id) {
        GeoRegionL5 region = requireRegion(id);
        GeoRegionL5AdminVO vo = toVo(region);
        return new GeoRegionL5AdminVO(
                vo.id(), vo.parentId(), vo.countryCode(), vo.name(), vo.nameEn(), vo.nameCh(),
                vo.code(), vo.level(), vo.path(), vo.isLeaf(), vo.latitude(), vo.longitude(),
                vo.status(), vo.sort(), vo.source(), vo.createdAt(), vo.updatedAt(),
                buildPathNames(region));
    }

    @Transactional
    public GeoRegionL5AdminVO create(GeoRegionL5CreateRequest req) {
        GeoRegion parent = requireL4Parent(req.parentId());
        String name = trimRequired(req.name(), "name");
        assertNoDuplicate(req.parentId(), name, trimOptional(req.nameEn()), null);

        LocalDateTime now = LocalDateTime.now();
        DuplicateKeyException lastDup = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long newId = idAllocator.allocate(5);
            String path = parent.getPath() + newId + "/";

            GeoRegionL5 row = new GeoRegionL5();
            row.setId(newId);
            row.setParentId(req.parentId());
            row.setCountryCode(parent.getCountryCode());
            row.setName(name);
            row.setNameEn(trimOptional(req.nameEn()));
            row.setNameCh(trimOptional(req.nameCh()));
            row.setCode(trimOptional(req.code()));
            row.setLevel(5);
            row.setRegionType("street");
            row.setPath(path);
            row.setIsLeaf(1);
            row.setLatitude(req.latitude());
            row.setLongitude(req.longitude());
            row.setSource("admin");
            row.setStatus(req.status() == null ? 1 : req.status());
            row.setSort(req.sort() == null ? 0 : req.sort());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                geoRegionL5Mapper.insert(row);
            } catch (DuplicateKeyException e) {
                lastDup = e;
                continue;
            }

            parent.setIsLeaf(0);
            parent.setUpdatedAt(now);
            geoRegionMapper.updateById(parent);

            geoDataCache.evictAfterMutation(newId, req.parentId(), parent.getCountryCode());
            return toVo(row);
        }
        throw lastDup != null ? lastDup : new BizException(ErrorCode.SYSTEM_ERROR);
    }

    @Transactional
    public void update(Long id, GeoRegionL5UpdateRequest req) {
        GeoRegionL5 row = requireRegion(id);
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
        geoRegionL5Mapper.updateById(row);
        geoDataCache.evictAfterMutation(id, parentId, countryCode);
    }

    @Transactional
    public void patchStatus(Long id, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        GeoRegionL5 row = requireRegion(id);
        row.setStatus(status);
        row.setUpdatedAt(LocalDateTime.now());
        geoRegionL5Mapper.updateById(row);
        geoDataCache.evictAfterMutation(id, row.getParentId(), row.getCountryCode());
    }

    private GeoRegionL5 requireRegion(Long id) {
        GeoRegionL5 row = geoRegionL5Mapper.findByIdAnyStatus(id);
        if (row == null) {
            throw new BizException(ErrorCode.REGION_NOT_FOUND);
        }
        return row;
    }

    private GeoRegion requireL4Parent(Long parentId) {
        if (parentId == null || parentId <= 0) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        GeoRegion parent = geoRegionMapper.findEnabledById(parentId);
        if (parent == null || parent.getLevel() == null || parent.getLevel() != 4) {
            throw new BizException(ErrorCode.PARENT_INVALID);
        }
        return parent;
    }

    private void assertNoDuplicate(Long parentId, String name, String nameEn, Long excludeId) {
        long cnt = geoRegionL5Mapper.countSameNameUnderParent(parentId, name, nameEn, excludeId);
        if (cnt > 0) {
            throw new BizException(ErrorCode.REGION_DUPLICATE);
        }
    }

    private String buildPathNames(GeoRegionL5 region) {
        List<Long> ids = PathUtil.parsePathIds(region.getPath());
        if (ids.isEmpty()) {
            return region.getName();
        }
        // path 前缀为 L1–L4（主表），末位为 L5 自身
        List<Long> ancestorIds = ids.stream().filter(id -> !id.equals(region.getId())).toList();
        Map<Long, String> nameMap = ancestorIds.isEmpty()
                ? Map.of()
                : geoRegionMapper.listByIds(ancestorIds).stream()
                .collect(Collectors.toMap(GeoRegion::getId, GeoRegion::getName, (a, b) -> a));
        List<String> names = new ArrayList<>();
        for (Long pid : ids) {
            if (pid.equals(region.getId())) {
                if (StringUtils.hasText(region.getName())) {
                    names.add(region.getName());
                }
                continue;
            }
            String n = nameMap.get(pid);
            if (StringUtils.hasText(n)) {
                names.add(n);
            }
        }
        return String.join(" / ", names);
    }

    private GeoRegionL5AdminVO toVo(GeoRegionL5 r) {
        return new GeoRegionL5AdminVO(
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

    public record GeoRegionL5AdminVO(
            Long id, Long parentId, String countryCode,
            String name, String nameEn, String nameCh, String code,
            Integer level, String path, Integer isLeaf,
            BigDecimal latitude, BigDecimal longitude,
            Integer status, Integer sort, String source,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            String fullPathName) {
    }

    public record GeoRegionL5CreateRequest(
            Long parentId, String name, String nameEn, String nameCh, String code,
            BigDecimal latitude, BigDecimal longitude, Integer sort, Integer status) {
    }

    public record GeoRegionL5UpdateRequest(
            String name, String nameEn, String nameCh, String code,
            BigDecimal latitude, BigDecimal longitude, Integer sort, Integer status) {
    }
}

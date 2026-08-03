package com.caopan.platform.geo.admin.access;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.entity.PlatformAccessClient;
import com.caopan.platform.geo.mapper.PlatformAccessClientMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 接入方白名单管理（GEO-002）。
 */
@Service
public class AccessClientAdminService {

    private static final Pattern CLIENT_CODE = Pattern.compile("^[A-Za-z0-9_\\-]{2,64}$");

    private final PlatformAccessClientMapper clientMapper;

    public AccessClientAdminService(PlatformAccessClientMapper clientMapper) {
        this.clientMapper = clientMapper;
    }

    public PageResult<AccessClientVO> page(String clientCode, Integer status, Integer allowIssue,
                                           int pageNum, int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        String codeFilter = StringUtils.hasText(clientCode) ? clientCode.trim() : null;
        long total = clientMapper.countAdmin(codeFilter, status, allowIssue);
        List<PlatformAccessClient> rows = clientMapper.pageAdmin(codeFilter, status, allowIssue,
                (pn - 1) * ps, ps);
        List<AccessClientVO> list = rows.stream().map(this::toVo).toList();
        return PageResult.of(total, pn, ps, list);
    }

    @Transactional
    public AccessClientVO create(AccessClientCreateRequest req) {
        String code = normalizeCode(req.clientCode());
        if (clientMapper.findByCode(code) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        PlatformAccessClient row = new PlatformAccessClient();
        row.setClientCode(code);
        row.setClientName(trimRequired(req.clientName(), "clientName"));
        row.setStatus(1);
        row.setAllowIssue(req.allowIssue() == null ? 1 : req.allowIssue());
        row.setRemark(trimOptional(req.remark()));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        clientMapper.insert(row);
        return toVo(row);
    }

    @Transactional
    public void update(Long id, AccessClientUpdateRequest req) {
        PlatformAccessClient row = requireClient(id);
        if (StringUtils.hasText(req.clientName())) {
            row.setClientName(req.clientName().trim());
        }
        if (req.allowIssue() != null) {
            row.setAllowIssue(req.allowIssue());
        }
        row.setRemark(trimOptional(req.remark()));
        row.setUpdatedAt(LocalDateTime.now());
        clientMapper.updateById(row);
    }

    @Transactional
    public void patchStatus(Long id, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        PlatformAccessClient row = requireClient(id);
        row.setStatus(status);
        row.setUpdatedAt(LocalDateTime.now());
        clientMapper.updateById(row);
    }

    private PlatformAccessClient requireClient(Long id) {
        PlatformAccessClient row = clientMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.CLIENT_NOT_FOUND);
        }
        return row;
    }

    private static String normalizeCode(String clientCode) {
        if (!StringUtils.hasText(clientCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String code = clientCode.trim();
        if (!CLIENT_CODE.matcher(code).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return code;
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

    private AccessClientVO toVo(PlatformAccessClient c) {
        return new AccessClientVO(
                c.getId(), c.getClientCode(), c.getClientName(),
                c.getStatus(), c.getAllowIssue(), c.getRemark(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    public record AccessClientVO(
            Long id, String clientCode, String clientName,
            Integer status, Integer allowIssue, String remark,
            java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
    }

    public record AccessClientCreateRequest(
            String clientCode, String clientName, Integer allowIssue, String remark) {
    }

    public record AccessClientUpdateRequest(
            String clientName, Integer allowIssue, String remark) {
    }
}

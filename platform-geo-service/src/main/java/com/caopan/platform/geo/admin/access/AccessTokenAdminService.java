package com.caopan.platform.geo.admin.access;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AccessTokenService;
import com.caopan.platform.geo.mapper.PlatformAccessTokenMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Token 管理端服务（GEO-002）。
 */
@Service
public class AccessTokenAdminService {

    private final PlatformAccessTokenMapper tokenMapper;
    private final AccessTokenService accessTokenService;

    public AccessTokenAdminService(
            PlatformAccessTokenMapper tokenMapper,
            AccessTokenService accessTokenService) {
        this.tokenMapper = tokenMapper;
        this.accessTokenService = accessTokenService;
    }

    public PageResult<AccessTokenVO> page(String clientCode, Integer status, int pageNum, int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        String code = clientCode == null ? null : clientCode.trim();
        long total = tokenMapper.countAdmin(code, status);
        List<TokenAdminRow> rows = tokenMapper.pageAdmin(code, status, (pn - 1) * ps, ps);
        List<AccessTokenVO> list = rows.stream()
                .map(r -> new AccessTokenVO(r.id(), r.clientCode(), r.tokenPrefix(), r.status(), r.createdAt()))
                .toList();
        return PageResult.of(total, pn, ps, list);
    }

    public Map<String, String> issue(String clientCode, String clientName) {
        AccessTokenService.IssuedToken issued = accessTokenService.issue(clientCode, clientName);
        return Map.of(
                "clientCode", issued.clientCode(),
                "token", issued.token(),
                "tokenPrefix", issued.tokenPrefix());
    }

    @Transactional
    public void revoke(Long id) {
        int n = tokenMapper.revokeById(id);
        if (n == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    /** Mapper 分页行。 */
    public record TokenAdminRow(
            Long id, String clientCode, String tokenPrefix, Integer status,
            java.time.LocalDateTime createdAt) {
    }

    public record AccessTokenVO(
            Long id, String clientCode, String tokenPrefix, Integer status,
            java.time.LocalDateTime createdAt) {
    }
}

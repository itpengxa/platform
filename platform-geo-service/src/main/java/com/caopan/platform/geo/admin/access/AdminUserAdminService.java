package com.caopan.platform.geo.admin.access;

import com.caopan.platform.common.api.PageResult;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.entity.PlatformAdminUser;
import com.caopan.platform.geo.mapper.PlatformAdminUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员用户维护（GEO-002）。
 */
@Service
public class AdminUserAdminService {

    private final PlatformAdminUserMapper userMapper;
    private final AdminAuthService adminAuthService;

    public AdminUserAdminService(PlatformAdminUserMapper userMapper, AdminAuthService adminAuthService) {
        this.userMapper = userMapper;
        this.adminAuthService = adminAuthService;
    }

    public PageResult<AdminUserVO> page(String username, Integer status, int pageNum, int pageSize) {
        int pn = Math.max(pageNum, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        String u = StringUtils.hasText(username) ? username.trim() : null;
        long total = userMapper.countAdmin(u, status);
        List<PlatformAdminUser> rows = userMapper.pageAdmin(u, status, (pn - 1) * ps, ps);
        return PageResult.of(total, pn, ps, rows.stream().map(this::toVo).toList());
    }

    public AdminUserVO create(CreateRequest req) {
        PlatformAdminUser user = adminAuthService.createUser(req.username(), req.password(), req.displayName());
        return toVo(user);
    }

    public void resetPassword(Long id, String newPassword) {
        adminAuthService.updatePassword(id, newPassword);
    }

    public void patchStatus(Long id, int status) {
        adminAuthService.patchStatus(id, status);
    }

    public void updateProfile(Long id, String displayName) {
        PlatformAdminUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (StringUtils.hasText(displayName)) {
            user.setDisplayName(displayName.trim());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    private AdminUserVO toVo(PlatformAdminUser u) {
        return new AdminUserVO(u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(),
                u.getCreatedAt(), u.getUpdatedAt());
    }

    public record AdminUserVO(
            Long id, String username, String displayName, Integer status,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record CreateRequest(String username, String password, String displayName) {
    }
}

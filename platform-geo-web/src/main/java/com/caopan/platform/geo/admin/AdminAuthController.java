package com.caopan.platform.geo.admin;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.admin.access.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端登录（GEO-002）。登录接口放行，其余需会话 Token。
 */
@RestController
@RequestMapping("/admin/platform/v1/auth")
public class AdminAuthController {

    public static final String HEADER_ADMIN_TOKEN = "X-Admin-Token";

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        AdminAuthService.LoginResult r = adminAuthService.login(
                req == null ? null : req.username(),
                req == null ? null : req.password());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", r.token());
        data.put("username", r.username());
        data.put("displayName", r.displayName());
        data.put("expireAt", r.expireAt() == null ? null : r.expireAt().toString());
        return Result.ok(data);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        adminAuthService.logout(extractToken(request));
        return Result.ok(null);
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest request) {
        AdminSessionCaller caller = adminAuthService.requireSession(extractToken(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", caller.userId());
        data.put("username", caller.username());
        data.put("displayName", caller.displayName());
        return Result.ok(data);
    }

    public static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_ADMIN_TOKEN);
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (StringUtils.hasText(auth) && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    public record LoginRequest(String username, String password) {
    }
}

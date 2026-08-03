package com.caopan.platform.geo.admin.support;

import com.caopan.platform.geo.access.AdminSessionCaller;
import com.caopan.platform.geo.admin.AdminAuthController;
import com.caopan.platform.geo.admin.access.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 从管理端请求解析操作人与客户端 IP。
 */
@Component
public class AdminOperatorResolver {

    private final AdminAuthService adminAuthService;

    public AdminOperatorResolver(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    public Resolved resolve(HttpServletRequest request) {
        String operator = "unknown";
        Long operatorId = null;
        String token = request == null ? null : AdminAuthController.extractToken(request);
        if (StringUtils.hasText(token)) {
            try {
                AdminSessionCaller caller = adminAuthService.requireSession(token);
                operator = caller.username();
                operatorId = caller.userId();
            } catch (Exception e) {
                operator = "legacy-secret";
            }
        }
        return new Resolved(operator, operatorId, clientIp(request));
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    public record Resolved(String operator, Long operatorId, String clientIp) {
    }
}

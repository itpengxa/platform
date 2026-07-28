package com.caopan.platform.geo.auth;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AccessTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 签发控制器（GEO-001 / platform-geo-web）。
 * <p>HTTP 接入层放在 web 模块，与 {@code GeoController} 同级职责；bootstrap 仅保留启动/Filter/切面。
 * 包路径为 {@code geo.auth}（非 {@code geo.controller}），故不被 {@code GeoAccessAspect} 要求携带业务 Token。
 * 当配置了 {@code platform.geo.auth.issue-secret} 时，必须携带 Header {@code X-Platform-Issue-Secret}。</p>
 */
@RestController
@RequestMapping("/api/platform/v1/auth/token")
public class TokenIssueController {

    public static final String HEADER_ISSUE_SECRET = "X-Platform-Issue-Secret";

    private final AccessTokenService accessTokenService;
    private final String issueSecret;

    /**
     * @param accessTokenService Token 签发/解析服务
     * @param issueSecret        签发口令；空表示未启用签发鉴权（仅 test 可空，online 由启动门禁强制）
     */
    public TokenIssueController(
            AccessTokenService accessTokenService,
            @Value("${platform.geo.auth.issue-secret:}") String issueSecret) {
        this.accessTokenService = accessTokenService;
        this.issueSecret = issueSecret == null ? "" : issueSecret.trim();
    }

    /**
     * 为接入方签发长效 Token（明文仅返回一次）。
     *
     * @param request clientCode 必填；clientName 可选
     * @param http    用于读取签发密钥 Header
     * @return data 含 clientCode / token / tokenPrefix
     */
    @PostMapping("/issue")
    public Result<Map<String, String>> issue(
            @RequestBody TokenIssueRequest request,
            HttpServletRequest http) {
        assertIssueSecret(http);
        String code = request == null ? null : request.getClientCode();
        String name = request == null ? null : request.getClientName();
        AccessTokenService.IssuedToken issued = accessTokenService.issue(code, name);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("clientCode", issued.getClientCode());
        data.put("token", issued.getToken());
        data.put("tokenPrefix", issued.getTokenPrefix());
        return Result.ok(data);
    }

    private void assertIssueSecret(HttpServletRequest http) {
        if (!StringUtils.hasText(issueSecret)) {
            return;
        }
        String provided = http.getHeader(HEADER_ISSUE_SECRET);
        if (!issueSecret.equals(provided)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 签发请求体。
     */
    public static class TokenIssueRequest {
        /** 接入方编码，必填，如 a / crm */
        private String clientCode;
        /** 展示名，可选 */
        private String clientName;

        /** @return 接入方编码 */
        public String getClientCode() { return clientCode; }
        public void setClientCode(String clientCode) { this.clientCode = clientCode; }

        /** @return 展示名 */
        public String getClientName() { return clientName; }
        public void setClientName(String clientName) { this.clientName = clientName; }
    }
}

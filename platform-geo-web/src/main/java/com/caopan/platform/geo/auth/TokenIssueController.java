package com.caopan.platform.geo.auth;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.geo.access.AccessTokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 签发控制器（GEO-001 / platform-geo-web）。
 * <p>HTTP 接入层放在 web 模块，与 {@code GeoController} 同级职责；bootstrap 仅保留启动/Filter/切面。
 * 包路径为 {@code geo.auth}（非 {@code geo.controller}），故不被 {@code GeoAccessAspect} 要求携带 Token，
 * 可用无 Token 的 curl 直接签发。同一 {@code clientCode} 再次签发会吊销旧 Token。</p>
 */
@RestController
@RequestMapping("/api/platform/v1/auth/token")
public class TokenIssueController {

    private final AccessTokenService accessTokenService;

    /**
     * @param accessTokenService Token 签发/解析服务
     */
    public TokenIssueController(AccessTokenService accessTokenService) {
        this.accessTokenService = accessTokenService;
    }

    /**
     * 为接入方签发长效 Token（明文仅返回一次）。
     *
     * @param request clientCode 必填；clientName 可选
     * @return data 含 clientCode / token / tokenPrefix
     */
    @PostMapping("/issue")
    public Result<Map<String, String>> issue(@RequestBody TokenIssueRequest request) {
        String code = request == null ? null : request.getClientCode();
        String name = request == null ? null : request.getClientName();
        AccessTokenService.IssuedToken issued = accessTokenService.issue(code, name);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("clientCode", issued.getClientCode());
        data.put("token", issued.getToken());
        data.put("tokenPrefix", issued.getTokenPrefix());
        return Result.ok(data);
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

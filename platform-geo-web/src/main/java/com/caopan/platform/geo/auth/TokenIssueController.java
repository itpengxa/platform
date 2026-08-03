package com.caopan.platform.geo.auth;

import com.caopan.platform.common.api.Result;
import com.caopan.platform.common.exception.BizException;
import com.caopan.platform.common.exception.ErrorCode;
import com.caopan.platform.geo.access.AccessTokenService;
import com.caopan.platform.geo.config.runtime.EffectiveAuthSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 签发控制器（GEO-001 / platform-geo-web）。
 * <p>HTTP 接入层放在 web 模块；当配置了 {@code platform.geo.auth.issue-secret} 时，
 * 必须携带 Header {@code X-Platform-Issue-Secret}。</p>
 */
@RestController
@RequestMapping("/api/platform/v1/auth/token")
public class TokenIssueController {

    public static final String HEADER_ISSUE_SECRET = "X-Platform-Issue-Secret";

    private final AccessTokenService accessTokenService;
    private final EffectiveAuthSettings authSettings;

    public TokenIssueController(AccessTokenService accessTokenService, EffectiveAuthSettings authSettings) {
        this.accessTokenService = accessTokenService;
        this.authSettings = authSettings;
    }

    /**
     * 为接入方签发长效 Token（明文仅返回一次）。
     */
    @PostMapping("/issue")
    public Result<Map<String, String>> issue(
            @RequestBody(required = false) TokenIssueRequest request,
            HttpServletRequest http) {
        assertIssueSecret(http);
        String code = request == null ? null : request.clientCode();
        String name = request == null ? null : request.clientName();
        AccessTokenService.IssuedToken issued = accessTokenService.issue(code, name);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("clientCode", issued.clientCode());
        data.put("token", issued.token());
        data.put("tokenPrefix", issued.tokenPrefix());
        return Result.ok(data);
    }

    private void assertIssueSecret(HttpServletRequest http) {
        String expected = authSettings.normalizedIssueSecret();
        if (!StringUtils.hasText(expected)) {
            return;
        }
        if (!expected.equals(http.getHeader(HEADER_ISSUE_SECRET))) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** 签发请求体（Jackson 可直接反序列化为 record）。 */
    public record TokenIssueRequest(String clientCode, String clientName) {
    }
}

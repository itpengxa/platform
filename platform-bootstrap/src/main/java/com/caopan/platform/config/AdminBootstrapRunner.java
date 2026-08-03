package com.caopan.platform.config;

import com.caopan.platform.geo.admin.access.AdminAuthService;
import com.caopan.platform.geo.config.GeoAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时若无管理员则种子默认账号。
 */
@Component
@Order(50)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final GeoAdminProperties adminProperties;
    private final AdminAuthService adminAuthService;

    public AdminBootstrapRunner(GeoAdminProperties adminProperties, AdminAuthService adminAuthService) {
        this.adminProperties = adminProperties;
        this.adminAuthService = adminAuthService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminProperties.enabled()) {
            return;
        }
        try {
            adminAuthService.ensureBootstrapAdmin();
        } catch (Exception e) {
            log.warn("bootstrap admin skipped/failed: {}", e.toString());
        }
    }
}

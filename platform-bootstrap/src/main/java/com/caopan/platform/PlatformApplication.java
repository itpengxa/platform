package com.caopan.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 2026-07-23 GEO-001 platform 模块化单体启动入口
 */
@SpringBootApplication(scanBasePackages = "com.caopan.platform")
@MapperScan("com.caopan.platform.geo.mapper")
@EnableCaching
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

package com.caopan.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 2026-07-24 GEO-001 platform 模块化单体启动入口
 */
@SpringBootApplication(scanBasePackages = "com.caopan.platform")
@MapperScan("com.caopan.platform.geo.mapper")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

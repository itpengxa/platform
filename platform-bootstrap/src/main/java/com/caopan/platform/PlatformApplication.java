package com.caopan.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Platform 应用启动类。Spring Boot 3.3 入口。
 * 启用虚拟线程（Virtual Threads）实现协程式高并发。
 * 自动扫描 platform-geo-service, platform-geo-web 等模块的 bean。
 */
@SpringBootApplication(scanBasePackages = "com.caopan.platform")
@MapperScan("com.caopan.platform.geo.mapper")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

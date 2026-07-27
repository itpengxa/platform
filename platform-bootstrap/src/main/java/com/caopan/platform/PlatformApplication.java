package com.caopan.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Platform 应用启动类（platform-bootstrap）。
 * <p>Spring Boot 入口，扫描 {@code com.caopan.platform} 下 geo-service / geo-web 等 Bean，
 * 并 {@code @MapperScan} 注册区划 Mapper。常用 profile：{@code test}（内网可关鉴权）、
 * {@code online}/{@code prod}（须开 DB Token 鉴权 + 建议限流 fail-closed）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.caopan.platform")
@MapperScan("com.caopan.platform.geo.mapper")
public class PlatformApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数（含 {@code --spring.profiles.active=}）
     */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

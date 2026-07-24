package com.caopan.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 国际化配置（platform-bootstrap，W10）。
 * <p>装配 MessageSource（classpath:messages）与 Accept-Language LocaleResolver，
 * 供错误文案中/英切换；缺省简体中文。与请求参数 lang 配合见 GlobalExceptionHandler。</p>
 */
@Configuration
public class I18nConfig {

    /**
     * @return 可热加载的 MessageSource
     */
    @Bean
    public ReloadableResourceBundleMessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return source;
    }

    /**
     * @return 基于 Accept-Language 的 Locale 解析器
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH));
        return resolver;
    }
}

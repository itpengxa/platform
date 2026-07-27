package com.caopan.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文案配置（platform-bootstrap）。
 * <p>当前仅英文：固定 {@link Locale#ENGLISH}，单一 {@code messages.properties}。
 * 区划展示名仍由请求参数 {@code lang}（local/en/zh）控制，与错误文案无关。</p>
 */
@Configuration
public class I18nConfig {

    /**
     * @return 可热加载的 MessageSource（英文）
     */
    @Bean
    public ReloadableResourceBundleMessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        return source;
    }

    /**
     * 固定英文，不再按 Accept-Language / 中文切换。
     *
     * @return FixedLocaleResolver(ENGLISH)
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.ENGLISH);
    }
}

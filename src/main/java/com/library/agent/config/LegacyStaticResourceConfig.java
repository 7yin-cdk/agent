package com.library.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 旧版静态调试页资源映射。
 * <p>
 * 前端已由 Vue3 单页应用接管 static 根目录；为保留被替代的旧调试页
 * （stream/rag/observability/test 等），将其移入 classpath:/static-legacy/，
 * 通过 /legacy/** 访问，避免被 Vite 构建产物清空。
 */
@Configuration
public class LegacyStaticResourceConfig {

    @Bean
    public WebMvcConfigurer legacyResourceConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/legacy/**")
                        .addResourceLocations("classpath:/static-legacy/");
            }
        };
    }
}

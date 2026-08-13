package com.straycat.statistra.config;

import com.straycat.statistra.security.CurrentOrganizationArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentOrganizationArgumentResolver currentOrganizationArgumentResolver;

    public WebConfig(CurrentOrganizationArgumentResolver currentOrganizationArgumentResolver) {
        this.currentOrganizationArgumentResolver = currentOrganizationArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentOrganizationArgumentResolver);
    }
}

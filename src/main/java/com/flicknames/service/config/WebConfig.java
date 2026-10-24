package com.flicknames.service.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Set;

/**
 * Global web configuration: CORS and the admin API key filter.
 *
 * Reads (GET/HEAD) are public data and allowed from any origin. Writes are only allowed from
 * the origins in CORS_ALLOWED_ORIGINS (the admin dashboard), and additionally require the
 * admin API key. CORS runs first so that rejected requests still carry CORS headers and the
 * browser can show the real 401 instead of a generic CORS error.
 */
@Configuration
public class WebConfig {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${cors.allowed-origins}") List<String> writeOrigins) {
        CorsConfiguration publicReads = new CorsConfiguration();
        publicReads.addAllowedOrigin("*");
        publicReads.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        publicReads.addAllowedHeader("*");
        publicReads.setAllowCredentials(false);

        CorsConfiguration adminWrites = new CorsConfiguration();
        adminWrites.setAllowedOrigins(writeOrigins);
        adminWrites.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        adminWrites.addAllowedHeader("*");
        adminWrites.setAllowCredentials(false);

        CorsConfigurationSource source = request -> {
            if (!request.getRequestURI().startsWith("/api/")) {
                return null;
            }
            return WRITE_METHODS.contains(effectiveMethod(request)) ? adminWrites : publicReads;
        };

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilterRegistration(AdminApiKeyFilter filter) {
        FilterRegistrationBean<AdminApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** For a CORS preflight, the method the browser is asking permission to use. */
    private static String effectiveMethod(HttpServletRequest request) {
        String requested = request.getHeader("Access-Control-Request-Method");
        return "OPTIONS".equals(request.getMethod()) && requested != null ? requested : request.getMethod();
    }
}

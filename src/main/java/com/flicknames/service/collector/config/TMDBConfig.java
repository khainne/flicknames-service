package com.flicknames.service.collector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "tmdb")
@Getter
@Setter
public class TMDBConfig {

    private String apiKey;
    private String baseUrl = "https://api.themoviedb.org/3";
    private String imageBaseUrl = "https://image.tmdb.org/t/p/";

    /**
     * Rate limiting configuration
     */
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class RateLimit {
        private int requestsPerSecond = 4;  // TMDB allows 50/second, we'll be conservative
        private boolean enabled = true;
    }
}

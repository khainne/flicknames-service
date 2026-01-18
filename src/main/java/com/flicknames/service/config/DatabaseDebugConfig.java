package com.flicknames.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@Slf4j
public class DatabaseDebugConfig {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:NOT_SET}")
    private String username;

    @Value("${PGHOST:NOT_SET}")
    private String pgHost;

    @Value("${PGPORT:NOT_SET}")
    private String pgPort;

    @Value("${PGDATABASE:NOT_SET}")
    private String pgDatabase;

    @Value("${PGUSER:NOT_SET}")
    private String pgUser;

    @Bean
    @Order(0)
    public CommandLineRunner logDatabaseConfig() {
        return args -> {
            log.info("=== DATABASE CONFIGURATION DEBUG ===");
            log.info("Datasource URL: {}", datasourceUrl);
            log.info("Datasource Username: {}", username);
            log.info("PGHOST: {}", pgHost);
            log.info("PGPORT: {}", pgPort);
            log.info("PGDATABASE: {}", pgDatabase);
            log.info("PGUSER: {}", pgUser);
            log.info("===================================");
        };
    }
}

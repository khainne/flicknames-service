package com.flicknames.service.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration
@Profile("production")
@Slf4j
public class RailwayDataSourceConfig {

    @Bean
    public DataSource dataSource() {
        String pgHost = System.getenv("PGHOST");
        String pgPort = System.getenv("PGPORT");
        String pgDatabase = System.getenv("PGDATABASE");
        String pgUser = System.getenv("PGUSER");
        String pgPassword = System.getenv("PGPASSWORD");

        log.info("=== RAILWAY POSTGRES CONFIGURATION ===");
        log.info("PGHOST: {}", pgHost != null ? pgHost : "NOT SET");
        log.info("PGPORT: {}", pgPort != null ? pgPort : "NOT SET");
        log.info("PGDATABASE: {}", pgDatabase != null ? pgDatabase : "NOT SET");
        log.info("PGUSER: {}", pgUser != null ? pgUser : "NOT SET");
        log.info("PGPASSWORD: {}", pgPassword != null ? "***SET***" : "NOT SET");

        // Build JDBC URL programmatically
        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s",
            pgHost != null ? pgHost : "localhost",
            pgPort != null ? pgPort : "5432",
            pgDatabase != null ? pgDatabase : "flicknames"
        );

        log.info("Constructed JDBC URL: {}", jdbcUrl);
        log.info("=====================================");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(pgUser != null ? pgUser : "postgres");
        config.setPassword(pgPassword != null ? pgPassword : "");
        config.setDriverClassName("org.postgresql.Driver");

        // Connection pool settings
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }
}

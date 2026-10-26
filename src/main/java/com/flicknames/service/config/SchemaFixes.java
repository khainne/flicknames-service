package com.flicknames.service.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema changes that {@code ddl-auto=update} does not make. Hibernate writes an enum's allowed values
 * into a check constraint only when it creates the table, so a value added later (the re-parse source
 * MODEL) is rejected by tables created earlier. Each fix is idempotent and runs at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaFixes implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        replaceCheck("character_reparse_rows", "character_reparse_rows_source_check",
            "source IN ('PARSER', 'LABEL', 'MODEL')");
    }

    private void replaceCheck(String table, String constraint, String condition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " CHECK (" + condition + ")");
        } catch (Exception e) {
            log.warn("Could not update check constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}

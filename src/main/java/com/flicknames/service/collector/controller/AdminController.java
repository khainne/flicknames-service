package com.flicknames.service.collector.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin", description = "Administrative endpoints")
public class AdminController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/db-stats")
    @Operation(summary = "Get database statistics",
               description = "Returns row counts for all main tables")
    public Map<String, Object> getDatabaseStats() {
        Map<String, Object> stats = new HashMap<>();
        Map<String, String> errors = new HashMap<>();

        try {
            // First, list all tables in the database
            var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
            );
            stats.put("existingTables", tables);

            // Get table counts with correct table names (pluralized)
            stats.put("movies", getTableCountWithError("movies", errors));
            stats.put("people", getTableCountWithError("people", errors));
            stats.put("characters", getTableCountWithError("characters", errors));
            stats.put("dataSources", getTableCountWithError("data_sources", errors));
            stats.put("credits", getTableCountWithError("credits", errors));

            if (!errors.isEmpty()) {
                stats.put("errors", errors);
            }
            stats.put("status", "success");

        } catch (Exception e) {
            log.error("Error getting database stats", e);
            stats.put("status", "error");
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    private Long getTableCountWithError(String tableName, Map<String, String> errors) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName,
                Long.class
            );
        } catch (Exception e) {
            log.error("Error counting table {}", tableName, e);
            errors.put(tableName, e.getMessage());
            return -1L;
        }
    }
}

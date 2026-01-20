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

        try {
            // Get table counts only - no samples to avoid column name issues
            stats.put("movies", getTableCount("movie"));
            stats.put("people", getTableCount("person"));
            stats.put("characters", getTableCount("screen_character"));
            stats.put("dataSources", getTableCount("data_source"));
            stats.put("movieCast", getTableCount("movie_cast"));
            stats.put("movieCrew", getTableCount("movie_crew"));

            stats.put("status", "success");

        } catch (Exception e) {
            log.error("Error getting database stats", e);
            stats.put("status", "error");
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    private Long getTableCount(String tableName) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName,
                Long.class
            );
        } catch (Exception e) {
            log.error("Error counting table {}", tableName, e);
            return -1L;
        }
    }
}

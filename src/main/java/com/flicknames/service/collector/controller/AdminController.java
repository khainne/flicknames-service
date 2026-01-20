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

    @GetMapping("/collection-stats")
    @Operation(summary = "Get collection statistics by year",
               description = "Shows movie counts by year, data source status, and coverage analysis")
    public Map<String, Object> getCollectionStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            // Movies by year (2015-2025) - PostgreSQL syntax
            String yearQuery = """
                SELECT EXTRACT(YEAR FROM release_date)::integer as year, COUNT(*) as count
                FROM movies
                WHERE EXTRACT(YEAR FROM release_date) BETWEEN 2015 AND 2025
                GROUP BY EXTRACT(YEAR FROM release_date)
                ORDER BY year DESC
            """;

            var moviesByYear = jdbcTemplate.queryForList(yearQuery);
            stats.put("moviesByYear", moviesByYear);

            // Data source fetch status
            String sourceStatusQuery = """
                SELECT status, COUNT(*) as count
                FROM data_sources
                WHERE source_type = 'TMDB' AND entity_type = 'MOVIE'
                GROUP BY status
            """;

            var fetchStatus = jdbcTemplate.queryForList(sourceStatusQuery);
            stats.put("fetchStatus", fetchStatus);

            // Total movies in range
            String totalQuery = """
                SELECT COUNT(*) FROM movies
                WHERE EXTRACT(YEAR FROM release_date) BETWEEN 2015 AND 2025
            """;

            Long totalMovies = jdbcTemplate.queryForObject(totalQuery, Long.class);
            stats.put("totalMovies2015to2025", totalMovies);

            // Success rate
            String successQuery = """
                SELECT COUNT(*) FROM data_sources
                WHERE source_type = 'TMDB' AND entity_type = 'MOVIE' AND status = 'SUCCESS'
            """;

            Long successfulFetches = jdbcTemplate.queryForObject(successQuery, Long.class);
            stats.put("successfulFetches", successfulFetches);

            stats.put("status", "success");

        } catch (Exception e) {
            log.error("Error getting collection stats", e);
            stats.put("status", "error");
            stats.put("error", e.getMessage());
        }

        return stats;
    }
}

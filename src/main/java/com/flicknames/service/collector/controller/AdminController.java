package com.flicknames.service.collector.controller;

import com.flicknames.service.entity.NamePattern;
import com.flicknames.service.entity.NamePattern.PatternType;
import com.flicknames.service.service.CharacterNameMigrationService;
import com.flicknames.service.service.NamePatternService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin", description = "Administrative endpoints")
public class AdminController {

    private final JdbcTemplate jdbcTemplate;
    private final CharacterNameMigrationService characterNameMigrationService;
    private final NamePatternService namePatternService;

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

    @GetMapping("/character-names/preview-migration")
    @Operation(summary = "Preview character name migration",
               description = "Shows what changes would be made by the name migration without actually applying them. " +
                            "Displays examples of characters that would be reclassified (e.g., 'Officer Daniels' -> TITLE_SURNAME)")
    public ResponseEntity<Map<String, Object>> previewCharacterNameMigration(
            @RequestParam(defaultValue = "10") int exampleLimit) {
        try {
            Map<String, Object> result = characterNameMigrationService.previewMigration(exampleLimit);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error in preview migration", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(error);
        }
    }

    @PostMapping("/character-names/migrate")
    @Operation(summary = "Migrate character names",
               description = "Re-processes all existing characters using the intelligent name parser. " +
                            "This will properly classify names (e.g., identify 'Officer Daniels' as TITLE_SURNAME) " +
                            "and set firstName to null for characters that don't have valid first names.")
    public Map<String, Object> migrateCharacterNames() {
        return characterNameMigrationService.migrateAllCharacters();
    }

    @PostMapping("/schema/add-character-columns")
    @Operation(summary = "Add missing columns to characters table",
               description = "Manually adds name_type and manually_verified columns to the characters table. " +
                            "This is needed when Hibernate auto-update doesn't run.")
    public Map<String, Object> addCharacterColumns() {
        Map<String, Object> result = new HashMap<>();
        try {
            // Add name_type column if it doesn't exist
            jdbcTemplate.execute(
                "ALTER TABLE characters ADD COLUMN IF NOT EXISTS name_type VARCHAR(20)"
            );

            // Add manually_verified column if it doesn't exist
            jdbcTemplate.execute(
                "ALTER TABLE characters ADD COLUMN IF NOT EXISTS manually_verified BOOLEAN DEFAULT FALSE"
            );

            // Create index on name_type
            jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_character_name_type ON characters(name_type)"
            );

            result.put("status", "success");
            result.put("message", "Schema updated successfully");
            log.info("Character table schema updated with name_type and manually_verified columns");
        } catch (Exception e) {
            log.error("Error updating schema", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ============== Name Pattern Management ==============

    @GetMapping("/patterns")
    @Operation(summary = "Get all name patterns",
               description = "Returns all patterns grouped by type (TITLE, ROLE_DESCRIPTOR, ADJECTIVE_PREFIX)")
    public Map<PatternType, List<NamePattern>> getAllPatterns() {
        return namePatternService.getAllPatternsGrouped();
    }

    @GetMapping("/patterns/stats")
    @Operation(summary = "Get pattern statistics",
               description = "Shows pattern counts in database and in parser cache")
    public Map<String, Object> getPatternStats() {
        return namePatternService.getPatternStats();
    }

    @PostMapping("/patterns/title")
    @Operation(summary = "Add a title pattern",
               description = "Add a new title/honorific (e.g., 'sergeant', 'detective'). " +
                            "Titles precede surnames and cause names to be classified as TITLE_SURNAME.")
    public ResponseEntity<Map<String, Object>> addTitle(
            @RequestParam String value,
            @RequestParam(required = false) String exampleName) {
        return namePatternService.addTitle(value, exampleName)
            .map(p -> ResponseEntity.ok(patternToMap(p, "Pattern added successfully")))
            .orElse(ResponseEntity.ok(Map.of("status", "exists", "message", "Pattern already exists")));
    }

    @PostMapping("/patterns/role-descriptor")
    @Operation(summary = "Add a role descriptor pattern",
               description = "Add a new role descriptor (e.g., 'bouncer', 'cashier'). " +
                            "Role descriptors identify non-personal names like 'Night Club Bouncer'.")
    public ResponseEntity<Map<String, Object>> addRoleDescriptor(
            @RequestParam String value,
            @RequestParam(required = false) String exampleName) {
        return namePatternService.addRoleDescriptor(value, exampleName)
            .map(p -> ResponseEntity.ok(patternToMap(p, "Pattern added successfully")))
            .orElse(ResponseEntity.ok(Map.of("status", "exists", "message", "Pattern already exists")));
    }

    @PostMapping("/patterns/adjective-prefix")
    @Operation(summary = "Add an adjective prefix pattern",
               description = "Add a new adjective prefix (e.g., 'grumpy', 'sleepy'). " +
                            "Adjective prefixes modify role descriptors like 'Grumpy Old Man'.")
    public ResponseEntity<Map<String, Object>> addAdjectivePrefix(
            @RequestParam String value,
            @RequestParam(required = false) String exampleName) {
        return namePatternService.addAdjectivePrefix(value, exampleName)
            .map(p -> ResponseEntity.ok(patternToMap(p, "Pattern added successfully")))
            .orElse(ResponseEntity.ok(Map.of("status", "exists", "message", "Pattern already exists")));
    }

    @DeleteMapping("/patterns")
    @Operation(summary = "Remove a pattern",
               description = "Remove a pattern by type and value")
    public ResponseEntity<Map<String, Object>> removePattern(
            @RequestParam PatternType type,
            @RequestParam String value) {
        boolean removed = namePatternService.removePattern(type, value);
        if (removed) {
            return ResponseEntity.ok(Map.of("status", "removed", "message", "Pattern removed successfully"));
        }
        return ResponseEntity.ok(Map.of("status", "not_found", "message", "Pattern not found"));
    }

    @PostMapping("/patterns/refresh")
    @Operation(summary = "Refresh pattern cache",
               description = "Reload patterns from database into the parser cache")
    public Map<String, Object> refreshPatternCache() {
        namePatternService.refreshCache();
        return namePatternService.getPatternStats();
    }

    @GetMapping("/patterns/test")
    @Operation(summary = "Test character name parsing",
               description = "Test how a character name would be parsed with current patterns")
    public Map<String, Object> testParse(@RequestParam String name) {
        return namePatternService.testParse(name);
    }

    private Map<String, Object> patternToMap(NamePattern pattern, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "created");
        result.put("message", message);
        result.put("pattern", Map.of(
            "id", pattern.getId(),
            "type", pattern.getPatternType().toString(),
            "value", pattern.getPatternValue(),
            "exampleName", pattern.getExampleName() != null ? pattern.getExampleName() : ""
        ));
        return result;
    }
}

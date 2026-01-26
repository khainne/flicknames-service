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
            stats.put("ssaNames", getTableCountWithError("ssa_names", errors));
            stats.put("ssaNameYearlyStats", getTableCountWithError("ssa_name_yearly_stats", errors));
            stats.put("ssaNameStateBreakdowns", getTableCountWithError("ssa_name_state_breakdowns", errors));
            stats.put("ssaImportMetadata", getTableCountWithError("ssa_import_metadata", errors));

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
    @Operation(summary = "Migrate character names (incremental)",
               description = "Re-processes characters using the intelligent name parser in batches to avoid timeout. " +
                            "Call repeatedly until isComplete=true. Default processes 5 batches (~5000 characters) per call.")
    public ResponseEntity<Map<String, Object>> migrateCharacterNamesIncremental(
            @RequestParam(defaultValue = "5") int batches) {
        try {
            long startTime = System.currentTimeMillis();
            Map<String, Object> result = characterNameMigrationService.migrateIncrementally(batches);
            long endTime = System.currentTimeMillis();
            result.put("status", "success");
            result.put("processingTimeMs", endTime - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error during character name migration", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(error);
        }
    }

    @PostMapping("/character-names/migrate-all")
    @Operation(summary = "Migrate all character names at once",
               description = "WARNING: May timeout on large datasets. Use /migrate (incremental) instead. " +
                            "Re-processes ALL characters using the intelligent name parser.")
    public ResponseEntity<Map<String, Object>> migrateAllCharacterNames() {
        try {
            long startTime = System.currentTimeMillis();
            Map<String, Object> result = characterNameMigrationService.migrateAllCharacters();
            long endTime = System.currentTimeMillis();
            result.put("status", "success");
            result.put("processingTimeMs", endTime - startTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error during character name migration", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("exceptionType", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(error);
        }
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

            // Make first_name nullable (required for TITLE_SURNAME, ROLE_DESCRIPTION, etc.)
            jdbcTemplate.execute(
                "ALTER TABLE characters ALTER COLUMN first_name DROP NOT NULL"
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

    @PostMapping("/schema/make-firstname-nullable")
    @Operation(summary = "Make first_name column nullable",
               description = "Drops NOT NULL constraint from first_name to allow titles, roles, etc. Returns constraint status.")
    public Map<String, Object> makeFirstNameNullable() {
        Map<String, Object> result = new HashMap<>();
        try {
            // Check current constraint status
            String checkSql = "SELECT is_nullable FROM information_schema.columns " +
                             "WHERE table_name = 'characters' AND column_name = 'first_name'";
            String beforeStatus = jdbcTemplate.queryForObject(checkSql, String.class);
            result.put("before", beforeStatus);

            // Drop NOT NULL constraint
            jdbcTemplate.execute("ALTER TABLE characters ALTER COLUMN first_name DROP NOT NULL");
            log.info("Dropped NOT NULL constraint from first_name column");

            // Verify it worked
            String afterStatus = jdbcTemplate.queryForObject(checkSql, String.class);
            result.put("after", afterStatus);
            result.put("status", "success");
            result.put("message", "first_name column is now nullable: " + afterStatus);
        } catch (Exception e) {
            log.error("Error making first_name nullable", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("exceptionType", e.getClass().getSimpleName());
        }
        return result;
    }

    @PostMapping("/schema/fix-ssa-data-year")
    @Operation(summary = "Make data_year column nullable in ssa_import_metadata",
               description = "Drops NOT NULL constraint from data_year to allow in-progress imports")
    public Map<String, Object> fixSsaDataYear() {
        Map<String, Object> result = new HashMap<>();
        try {
            // Check current constraint status
            String checkSql = "SELECT is_nullable FROM information_schema.columns " +
                             "WHERE table_name = 'ssa_import_metadata' AND column_name = 'data_year'";
            String beforeStatus = jdbcTemplate.queryForObject(checkSql, String.class);
            result.put("before", beforeStatus);

            // Drop NOT NULL constraint
            jdbcTemplate.execute("ALTER TABLE ssa_import_metadata ALTER COLUMN data_year DROP NOT NULL");
            log.info("Dropped NOT NULL constraint from ssa_import_metadata.data_year column");

            // Verify it worked
            String afterStatus = jdbcTemplate.queryForObject(checkSql, String.class);
            result.put("after", afterStatus);
            result.put("status", "success");
            result.put("message", "data_year column is now nullable: " + afterStatus);
        } catch (Exception e) {
            log.error("Error making data_year nullable", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("exceptionType", e.getClass().getSimpleName());
        }
        return result;
    }

    @GetMapping("/character-names/sample")
    @Operation(summary = "Get sample character names for validation",
               description = "Returns random sample of character names for validation before migration")
    public Map<String, Object> getSampleCharacterNames(
            @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Get random sample of character names
            List<Map<String, Object>> names = jdbcTemplate.queryForList(
                "SELECT full_name, first_name, last_name FROM characters " +
                "ORDER BY RANDOM() LIMIT ?", limit
            );

            result.put("status", "success");
            result.put("count", names.size());
            result.put("names", names);
        } catch (Exception e) {
            log.error("Error getting sample names", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/character-names/search")
    @Operation(summary = "Search character names",
               description = "Search for character names containing specific text")
    public Map<String, Object> searchCharacterNames(
            @RequestParam String search,
            @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> names = jdbcTemplate.queryForList(
                "SELECT full_name, first_name, last_name FROM characters " +
                "WHERE full_name ILIKE ? LIMIT ?",
                "%" + search + "%", limit
            );

            result.put("status", "success");
            result.put("count", names.size());
            result.put("search", search);
            result.put("names", names);
        } catch (Exception e) {
            log.error("Error searching names", e);
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

    @GetMapping("/ssa/spot-check")
    @Operation(summary = "Spot check SSA data",
               description = "Returns sample SSA data for validation")
    public Map<String, Object> spotCheckSsaData() {
        Map<String, Object> result = new HashMap<>();
        try {
            // Check years coverage
            String yearsSql = "SELECT DISTINCT year FROM ssa_name_yearly_stats ORDER BY year";
            List<Integer> years = jdbcTemplate.queryForList(yearsSql, Integer.class);
            result.put("years", years);

            // Check top names for 2024
            String top2024Sql = """
                SELECT n.name, n.sex, s.year, s.count, s.rank
                FROM ssa_name_yearly_stats s
                JOIN ssa_names n ON s.ssa_name_id = n.id
                WHERE s.year = 2024
                ORDER BY s.count DESC
                LIMIT 10
            """;
            List<Map<String, Object>> top2024 = jdbcTemplate.queryForList(top2024Sql);
            result.put("top2024Names", top2024);

            // Check a specific popular name across years (Emma for females)
            String emmaSql = """
                SELECT n.name, n.sex, s.year, s.count, s.rank
                FROM ssa_name_yearly_stats s
                JOIN ssa_names n ON s.ssa_name_id = n.id
                WHERE n.name = 'Emma' AND n.sex = 'F'
                ORDER BY s.year DESC
            """;
            List<Map<String, Object>> emmaStats = jdbcTemplate.queryForList(emmaSql);
            result.put("emmaAcrossYears", emmaStats);

            // Check counts by year
            String countsBySql = """
                SELECT year, COUNT(*) as name_count, SUM(count) as total_babies
                FROM ssa_name_yearly_stats
                GROUP BY year
                ORDER BY year DESC
            """;
            List<Map<String, Object>> countsByYear = jdbcTemplate.queryForList(countsBySql);
            result.put("countsByYear", countsByYear);

            // Sample some unique names
            String uniqueSql = """
                SELECT n.name, n.sex, s.year, s.count
                FROM ssa_name_yearly_stats s
                JOIN ssa_names n ON s.ssa_name_id = n.id
                ORDER BY RANDOM()
                LIMIT 20
            """;
            List<Map<String, Object>> randomSample = jdbcTemplate.queryForList(uniqueSql);
            result.put("randomSample", randomSample);

            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error spot checking SSA data", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/debug/files")
    @Operation(summary = "List files in application directory",
               description = "Debug endpoint to check what files are available")
    public Map<String, Object> listFiles() {
        Map<String, Object> result = new HashMap<>();
        try {
            String workingDir = System.getProperty("user.dir");
            result.put("workingDir", workingDir);

            java.io.File dir = new java.io.File(workingDir);
            String[] files = dir.list();
            result.put("files", files != null ? java.util.Arrays.asList(files) : java.util.Collections.emptyList());
            result.put("status", "success");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
}

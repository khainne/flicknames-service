package com.flicknames.service.collector.ssa;

import com.flicknames.service.entity.SsaImportMetadata;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ssa")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "SSA Import", description = "APIs for importing baby name data from Social Security Administration")
public class SsaImportController {

    private final SsaImportService ssaImportService;

    @PostMapping("/import/national")
    @Operation(summary = "Import national SSA baby names data",
               description = "Downloads and imports the national baby names dataset from SSA. " +
                           "Data includes names from 1880 to present with yearly counts.")
    public ResponseEntity<Map<String, Object>> importNationalData(
            @Parameter(description = "Force reimport even if data hasn't changed")
            @RequestParam(defaultValue = "false") boolean force,
            @Parameter(description = "Minimum year to import (inclusive)")
            @RequestParam(required = false) Integer minYear,
            @Parameter(description = "Maximum year to import (inclusive)")
            @RequestParam(required = false) Integer maxYear) {

        log.info("Starting national SSA import (force={}, years={}-{})", force, minYear, maxYear);

        try {
            SsaImportService.SsaImportResult result = ssaImportService.importNationalData(force, minYear, maxYear);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "recordCount", result.recordCount(),
                    "nameCount", result.nameCount(),
                    "maxYear", result.maxYear(),
                    "message", result.message()
            ));
        } catch (Exception e) {
            log.error("Failed to import national SSA data", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/national-from-url")
    @Operation(summary = "Import national SSA data from custom URL",
               description = "Downloads and imports national baby names data from a custom URL. " +
                           "Useful when SSA website is blocked or for testing with alternative sources.")
    public ResponseEntity<Map<String, Object>> importNationalDataFromUrl(
            @Parameter(description = "Custom URL to download ZIP file from")
            @RequestParam String url,
            @Parameter(description = "Force reimport even if data hasn't changed")
            @RequestParam(defaultValue = "false") boolean force,
            @Parameter(description = "Minimum year to import (inclusive)")
            @RequestParam(required = false) Integer minYear,
            @Parameter(description = "Maximum year to import (inclusive)")
            @RequestParam(required = false) Integer maxYear) {

        log.info("Starting national SSA import from custom URL: {}", url);

        try {
            SsaImportService.SsaImportResult result = ssaImportService.importNationalDataFromUrl(url, force, minYear, maxYear);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "recordCount", result.recordCount(),
                    "nameCount", result.nameCount(),
                    "maxYear", result.maxYear(),
                    "message", result.message()
            ));
        } catch (Exception e) {
            log.error("Failed to import national SSA data from URL: {}", url, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/state")
    @Operation(summary = "Import state-level SSA baby names data",
               description = "Downloads and imports the state-level baby names dataset from SSA. " +
                           "Must be run after national import. Data includes state breakdowns from 1910 to present.")
    public ResponseEntity<Map<String, Object>> importStateData(
            @Parameter(description = "Force reimport even if data hasn't changed")
            @RequestParam(defaultValue = "false") boolean force,
            @Parameter(description = "Minimum year to import (inclusive)")
            @RequestParam(required = false) Integer minYear,
            @Parameter(description = "Maximum year to import (inclusive)")
            @RequestParam(required = false) Integer maxYear) {

        log.info("Starting state SSA import (force={}, years={}-{})", force, minYear, maxYear);

        try {
            SsaImportService.SsaImportResult result = ssaImportService.importStateData(force, minYear, maxYear);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "recordCount", result.recordCount(),
                    "maxYear", result.maxYear(),
                    "message", result.message()
            ));
        } catch (Exception e) {
            log.error("Failed to import state SSA data", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/state-from-url")
    @Operation(summary = "Import state-level SSA data from custom URL",
               description = "Downloads and imports state-level baby names data from a custom URL. " +
                           "Must be run after national import. Useful when SSA website is blocked.")
    public ResponseEntity<Map<String, Object>> importStateDataFromUrl(
            @Parameter(description = "Custom URL to download ZIP file from")
            @RequestParam String url,
            @Parameter(description = "Force reimport even if data hasn't changed")
            @RequestParam(defaultValue = "false") boolean force,
            @Parameter(description = "Minimum year to import (inclusive)")
            @RequestParam(required = false) Integer minYear,
            @Parameter(description = "Maximum year to import (inclusive)")
            @RequestParam(required = false) Integer maxYear) {

        log.info("Starting state SSA import from custom URL: {}", url);

        try {
            SsaImportService.SsaImportResult result = ssaImportService.importStateDataFromUrl(url, force, minYear, maxYear);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "recordCount", result.recordCount(),
                    "maxYear", result.maxYear(),
                    "message", result.message()
            ));
        } catch (Exception e) {
            log.error("Failed to import state SSA data from URL: {}", url, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/local")
    @Operation(summary = "Import SSA data from local ZIP file",
               description = "Import SSA data from a local ZIP file for testing or manual imports")
    public ResponseEntity<Map<String, Object>> importFromLocalFile(
            @Parameter(description = "Path to local ZIP file")
            @RequestParam String filePath,
            @Parameter(description = "Dataset type: NATIONAL or STATE")
            @RequestParam SsaImportMetadata.DatasetType datasetType,
            @Parameter(description = "Minimum year to import (inclusive)")
            @RequestParam(required = false) Integer minYear,
            @Parameter(description = "Maximum year to import (inclusive)")
            @RequestParam(required = false) Integer maxYear) {

        log.info("Starting local SSA import from {} (type={}, years={}-{})",
                filePath, datasetType, minYear, maxYear);

        try {
            SsaImportService.SsaImportResult result = ssaImportService.importFromLocalFile(
                    Paths.get(filePath), datasetType, minYear, maxYear);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "recordCount", result.recordCount(),
                    "nameCount", result.nameCount(),
                    "maxYear", result.maxYear(),
                    "message", result.message()
            ));
        } catch (Exception e) {
            log.error("Failed to import local SSA data", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/rankings/calculate")
    @Operation(summary = "Calculate rankings for a specific year",
               description = "Calculates rank and proportion for all names in a given year. " +
                           "Should be run after import to populate ranking data.")
    public ResponseEntity<Map<String, String>> calculateRankings(
            @Parameter(description = "Year to calculate rankings for")
            @RequestParam Integer year) {

        log.info("Calculating rankings for year {}", year);

        try {
            ssaImportService.calculateRankings(year);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("Rankings calculated for year %d", year)
            ));
        } catch (Exception e) {
            log.error("Failed to calculate rankings for year {}", year, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/rankings/calculate-range")
    @Operation(summary = "Calculate rankings for a range of years",
               description = "Calculates rankings for all years in the specified range")
    public ResponseEntity<Map<String, Object>> calculateRankingsRange(
            @Parameter(description = "Start year (inclusive)")
            @RequestParam Integer startYear,
            @Parameter(description = "End year (inclusive)")
            @RequestParam Integer endYear) {

        log.info("Calculating rankings for years {} to {}", startYear, endYear);

        try {
            int yearsProcessed = 0;
            for (int year = startYear; year <= endYear; year++) {
                ssaImportService.calculateRankings(year);
                yearsProcessed++;
            }

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "yearsProcessed", yearsProcessed,
                    "message", String.format("Rankings calculated for years %d to %d", startYear, endYear)
            ));
        } catch (Exception e) {
            log.error("Failed to calculate rankings for range {}-{}", startYear, endYear, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/rankings/state")
    @Operation(summary = "Calculate state rankings for a specific year and state")
    public ResponseEntity<Map<String, String>> calculateStateRankings(
            @Parameter(description = "Year to calculate rankings for")
            @RequestParam Integer year,
            @Parameter(description = "Two-letter state code")
            @RequestParam String stateCode) {

        log.info("Calculating state rankings for {} in {}", stateCode, year);

        try {
            ssaImportService.calculateStateRankings(year, stateCode.toUpperCase());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("State rankings calculated for %s in %d", stateCode, year)
            ));
        } catch (Exception e) {
            log.error("Failed to calculate state rankings for {} in {}", stateCode, year, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/import/history")
    @Operation(summary = "Get import history",
               description = "Returns a list of all SSA import operations with their status")
    public ResponseEntity<List<SsaImportMetadata>> getImportHistory() {
        return ResponseEntity.ok(ssaImportService.getImportHistory());
    }

    @GetMapping("/import/status")
    @Operation(summary = "Check if update is available",
               description = "Checks if new SSA data might be available based on last import date")
    public ResponseEntity<Map<String, Object>> checkUpdateStatus() {
        boolean updateLikely = ssaImportService.isUpdateLikelyAvailable();

        return ResponseEntity.ok(Map.of(
                "updateLikelyAvailable", updateLikely,
                "message", updateLikely
                        ? "New SSA data may be available. Run import with force=true to check."
                        : "SSA data appears to be up to date."
        ));
    }
}

package com.flicknames.service.collector.imdb;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/imdb")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "IMDb Import", description = "APIs for importing bulk data from IMDb datasets")
public class IMDbImportController {

    private final IMDbImportService imdbImportService;

    @PostMapping("/import/movies")
    @Operation(summary = "Import movies from title.basics.tsv.gz file")
    public ResponseEntity<Map<String, String>> importMovies(
            @RequestParam String filePath,
            @RequestParam(defaultValue = "2000") int minYear,
            @RequestParam(defaultValue = "2025") int maxYear) {

        log.info("Starting movie import from {} (years {}-{})", filePath, minYear, maxYear);

        try {
            imdbImportService.importMovies(Paths.get(filePath), minYear, maxYear);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("Successfully imported movies from %s", filePath)
            ));
        } catch (Exception e) {
            log.error("Failed to import movies", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/people")
    @Operation(summary = "Import people from name.basics.tsv.gz file")
    public ResponseEntity<Map<String, String>> importPeople(
            @RequestParam String principalsFilePath,
            @RequestParam String peopleFilePath) {

        log.info("Starting people import from {} (filtering from {})", peopleFilePath, principalsFilePath);

        try {
            // First extract which people are actually referenced
            Set<String> referencedPeople = imdbImportService.extractReferencedPeople(Paths.get(principalsFilePath));

            // Then import only those people
            imdbImportService.importPeople(Paths.get(peopleFilePath), referencedPeople);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("Successfully imported %d people", referencedPeople.size())
            ));
        } catch (Exception e) {
            log.error("Failed to import people", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/import/credits")
    @Operation(summary = "Import credits from title.principals.tsv.gz file")
    public ResponseEntity<Map<String, String>> importCredits(@RequestParam String filePath) {

        log.info("Starting credits import from {}", filePath);

        try {
            imdbImportService.importCredits(Paths.get(filePath));
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("Successfully imported credits from %s", filePath)
            ));
        } catch (Exception e) {
            log.error("Failed to import credits", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }
}

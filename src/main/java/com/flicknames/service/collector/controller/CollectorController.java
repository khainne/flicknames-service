package com.flicknames.service.collector.controller;

import com.flicknames.service.collector.config.CollectorScheduleConfig;
import com.flicknames.service.collector.dto.ComprehensiveCollectionResult;
import com.flicknames.service.collector.service.DataCollectorService;
import com.flicknames.service.entity.Movie;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/collector")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Data Collector", description = "APIs for collecting movie and credit data from TMDB")
public class CollectorController {

    private final DataCollectorService collectorService;
    private final CollectorScheduleConfig scheduleConfig;

    @PostMapping("/movie/{tmdbMovieId}")
    @Operation(summary = "Collect a single movie by TMDB ID")
    public ResponseEntity<Movie> collectMovie(@PathVariable Long tmdbMovieId) {
        log.info("Collecting movie with TMDB ID: {}", tmdbMovieId);
        Movie movie = collectorService.collectMovie(tmdbMovieId);

        if (movie == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(movie);
    }

    @PostMapping("/popular")
    @Operation(summary = "Collect popular movies from TMDB")
    public ResponseEntity<Map<String, String>> collectPopularMovies(
            @RequestParam(defaultValue = "1") int pages) {
        log.info("Collecting {} pages of popular movies", pages);

        try {
            collectorService.collectPopularMovies(pages);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("Successfully collected %d pages of popular movies", pages)
            ));
        } catch (Exception e) {
            log.error("Error collecting popular movies", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/year/{year}")
    @Operation(summary = "Collect top box office movies for a specific year")
    public ResponseEntity<Map<String, String>> collectMoviesByYear(
            @PathVariable int year,
            @RequestParam(defaultValue = "1") int pages) {
        log.info("Collecting {} pages of movies for year {}", pages, year);

        try {
            collectorService.collectMoviesByYear(year, pages);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", String.format("Successfully collected %d pages of movies for year %d", pages, year)
            ));
        } catch (Exception e) {
            log.error("Error collecting movies for year {}", year, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/run-scheduled")
    @Operation(summary = "Manually trigger scheduled collection tasks",
               description = "Runs both popular movies and current year collection with configured settings")
    public ResponseEntity<Map<String, Object>> runScheduledCollection() {
        log.info("Manually triggering scheduled collection tasks");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> tasks = new HashMap<>();

        try {
            // Run popular movies collection
            if (scheduleConfig.getPopular().isEnabled()) {
                int popularPages = scheduleConfig.getPopular().getPages();
                log.info("Running popular movies collection ({} pages)", popularPages);

                try {
                    collectorService.collectPopularMovies(popularPages);
                    tasks.put("popularMovies", Map.of(
                        "status", "success",
                        "pages", popularPages,
                        "estimatedMovies", popularPages * 20
                    ));
                } catch (Exception e) {
                    log.error("Failed to collect popular movies", e);
                    tasks.put("popularMovies", Map.of(
                        "status", "error",
                        "message", e.getMessage()
                    ));
                }
            } else {
                tasks.put("popularMovies", Map.of("status", "disabled"));
            }

            // Run current year collection
            if (scheduleConfig.getCurrentYear().isEnabled()) {
                int currentYear = Year.now().getValue();
                int yearPages = scheduleConfig.getCurrentYear().getPages();
                log.info("Running current year ({}) collection ({} pages)", currentYear, yearPages);

                try {
                    collectorService.collectMoviesByYear(currentYear, yearPages);
                    tasks.put("currentYearMovies", Map.of(
                        "status", "success",
                        "year", currentYear,
                        "pages", yearPages,
                        "estimatedMovies", yearPages * 20
                    ));
                } catch (Exception e) {
                    log.error("Failed to collect current year movies", e);
                    tasks.put("currentYearMovies", Map.of(
                        "status", "error",
                        "message", e.getMessage()
                    ));
                }
            } else {
                tasks.put("currentYearMovies", Map.of("status", "disabled"));
            }

            result.put("status", "completed");
            result.put("tasks", tasks);
            result.put("message", "Scheduled collection tasks executed");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error running scheduled collection", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @PostMapping("/comprehensive/year/{year}")
    @Operation(summary = "Comprehensive collection for a year using multiple strategies",
               description = "Uses multiple sorting strategies (popularity, vote_count, release_date, alphabetical) to maximize coverage")
    public ResponseEntity<Map<String, Object>> collectYearComprehensive(
            @PathVariable int year,
            @RequestParam(defaultValue = "false") boolean usOnly,
            @RequestParam(defaultValue = "50") int maxPagesPerStrategy) {

        log.info("Starting comprehensive collection for year {} (US only: {}, max pages: {})",
                year, usOnly, maxPagesPerStrategy);

        try {
            ComprehensiveCollectionResult result =
                    collectorService.collectYearComprehensive(year, usOnly, maxPagesPerStrategy);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("year", year);
            response.put("usOnly", usOnly);
            response.put("duration_minutes", result.getDurationMinutes());
            response.put("duration_seconds", result.getDurationSeconds());
            response.put("strategies", result.getStrategyResults());
            response.put("total_movies_collected", result.getTotalMoviesCollected());
            response.put("message", String.format("Collected %d movies for year %d using %d strategies",
                    result.getTotalMoviesCollected(), year, result.getStrategyResults().size()));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in comprehensive collection for year {}", year, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "year", year,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/comprehensive/years")
    @Operation(summary = "Comprehensive collection for multiple years",
               description = "Run comprehensive collection across multiple years (e.g., 2015-2025)")
    public ResponseEntity<Map<String, Object>> collectYearsComprehensive(
            @RequestParam(defaultValue = "2015") int startYear,
            @RequestParam(defaultValue = "2025") int endYear,
            @RequestParam(defaultValue = "false") boolean usOnly,
            @RequestParam(defaultValue = "50") int maxPagesPerStrategy) {

        log.info("Starting comprehensive collection for years {}-{} (US only: {})",
                startYear, endYear, usOnly);

        Map<String, Object> overallResults = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> yearResults = new LinkedHashMap<>();

        try {
            int totalMovies = 0;
            long totalDurationSeconds = 0;

            for (int year = startYear; year <= endYear; year++) {
                try {
                    ComprehensiveCollectionResult result =
                            collectorService.collectYearComprehensive(year, usOnly, maxPagesPerStrategy);

                    Map<String, Object> yearData = new LinkedHashMap<>();
                    yearData.put("movies_collected", result.getTotalMoviesCollected());
                    yearData.put("duration_minutes", result.getDurationMinutes());
                    yearData.put("strategies", result.getStrategyResults());

                    yearResults.put(year, yearData);
                    totalMovies += result.getTotalMoviesCollected();
                    totalDurationSeconds += result.getDurationSeconds();

                } catch (Exception e) {
                    log.error("Error collecting year {}", year, e);
                    yearResults.put(year, Map.of(
                            "status", "error",
                            "message", e.getMessage()
                    ));
                }
            }

            overallResults.put("status", "success");
            overallResults.put("startYear", startYear);
            overallResults.put("endYear", endYear);
            overallResults.put("usOnly", usOnly);
            overallResults.put("total_movies_collected", totalMovies);
            overallResults.put("total_duration_minutes", totalDurationSeconds / 60);
            overallResults.put("years", yearResults);

            return ResponseEntity.ok(overallResults);

        } catch (Exception e) {
            log.error("Error in multi-year comprehensive collection", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/segmented/year/{year}")
    @Operation(summary = "Segmented collection for high-volume years",
               description = "Uses vote count segmentation to overcome the 500-page limit for years with >10k results")
    public ResponseEntity<Map<String, String>> collectYearSegmented(
            @PathVariable int year,
            @RequestParam(defaultValue = "false") boolean usOnly) {

        log.info("Starting segmented collection for year {} (US only: {})", year, usOnly);

        try {
            collectorService.collectYearSegmented(year, usOnly);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "year", String.valueOf(year),
                    "message", String.format("Completed segmented collection for year %d", year)
            ));

        } catch (Exception e) {
            log.error("Error in segmented collection for year {}", year, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "year", String.valueOf(year),
                    "message", e.getMessage()
            ));
        }
    }
}

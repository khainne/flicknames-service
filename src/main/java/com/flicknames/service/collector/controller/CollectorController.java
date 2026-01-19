package com.flicknames.service.collector.controller;

import com.flicknames.service.collector.config.CollectorScheduleConfig;
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
}

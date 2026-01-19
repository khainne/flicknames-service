package com.flicknames.service.collector.controller;

import com.flicknames.service.collector.service.DataCollectorService;
import com.flicknames.service.entity.Movie;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/collector")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Data Collector", description = "APIs for collecting movie and credit data from TMDB")
public class CollectorController {

    private final DataCollectorService collectorService;

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
}

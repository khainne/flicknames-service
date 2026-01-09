package com.flicknames.service.controller;

import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.MovieWithCreditsDTO;
import com.flicknames.service.service.MovieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
@Tag(name = "Movies", description = "Movie information APIs")
public class MovieController {

    private final MovieService movieService;

    @GetMapping("/{id}")
    @Operation(summary = "Get movie details by ID")
    public ResponseEntity<MovieDTO> getMovieById(@PathVariable Long id) {
        return ResponseEntity.ok(movieService.getMovieById(id));
    }

    @GetMapping("/{id}/credits")
    @Operation(summary = "Get movie with full cast and crew")
    public ResponseEntity<MovieWithCreditsDTO> getMovieWithCredits(@PathVariable Long id) {
        return ResponseEntity.ok(movieService.getMovieWithCredits(id));
    }

    @GetMapping("/search")
    @Operation(summary = "Search for movies by title")
    public ResponseEntity<Page<MovieDTO>> searchMovies(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(movieService.searchMovies(q, PageRequest.of(page, size)));
    }

    @GetMapping("/box-office/current")
    @Operation(summary = "Get current box office movies")
    public ResponseEntity<List<MovieDTO>> getCurrentBoxOffice(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(movieService.getCurrentBoxOffice(PageRequest.of(0, limit)));
    }

    @GetMapping("/box-office/year/{year}")
    @Operation(summary = "Get top movies by year")
    public ResponseEntity<List<MovieDTO>> getTopMoviesByYear(
            @PathVariable int year,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(movieService.getTopMoviesByYear(year, PageRequest.of(0, limit)));
    }

    @GetMapping("/recent")
    @Operation(summary = "Get recent movie releases")
    public ResponseEntity<List<MovieDTO>> getRecentReleases(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(movieService.getRecentReleases(PageRequest.of(0, limit)));
    }
}

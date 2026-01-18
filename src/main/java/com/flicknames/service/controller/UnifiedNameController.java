package com.flicknames.service.controller;

import com.flicknames.service.dto.TrendingNameDTO;
import com.flicknames.service.service.UnifiedNameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/all-names")
@RequiredArgsConstructor
@Tag(name = "All Names (Unified)", description = "Combined person AND character name trending APIs - aggregates both real people and fictional characters")
public class UnifiedNameController {

    private final UnifiedNameService unifiedNameService;

    @GetMapping("/trending/weekly")
    @Operation(summary = "Get trending names from BOTH people and characters this week",
               description = "Returns first names aggregated across all people AND characters from this week's box office. " +
                            "For example, 'Peter' combines Peter Parker (character), Peter Quill (character), and any real people named Peter.")
    public ResponseEntity<List<TrendingNameDTO>> getAllTrendingWeekly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(unifiedNameService.getAllTrendingNamesWeekly(limit));
    }

    @GetMapping("/trending/yearly")
    @Operation(summary = "Get highest grossing names from BOTH people and characters this year",
               description = "Returns first names from current year movies, combining both real people and fictional characters")
    public ResponseEntity<List<TrendingNameDTO>> getAllTrendingYearly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(unifiedNameService.getAllTrendingNamesCurrentYear(limit));
    }

    @GetMapping("/trending/yearly/{year}")
    @Operation(summary = "Get highest grossing names from BOTH people and characters for a specific year",
               description = "Returns first names from the specified year, combining both real people and fictional characters")
    public ResponseEntity<List<TrendingNameDTO>> getAllTrendingByYear(
            @PathVariable int year,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(unifiedNameService.getAllTrendingNamesYearly(year, limit));
    }
}

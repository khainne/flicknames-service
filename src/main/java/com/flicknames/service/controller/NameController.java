package com.flicknames.service.controller;

import com.flicknames.service.dto.TrendingPersonDTO;
import com.flicknames.service.service.NameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/names")
@RequiredArgsConstructor
@Tag(name = "Names", description = "Name discovery and trending APIs")
public class NameController {

    private final NameService nameService;

    @GetMapping("/trending/weekly")
    @Operation(summary = "Get trending names from this week's box office")
    public ResponseEntity<List<TrendingPersonDTO>> getTrendingWeekly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(nameService.getTrendingNamesWeekly(limit));
    }

    @GetMapping("/trending/yearly")
    @Operation(summary = "Get highest grossing names this year")
    public ResponseEntity<List<TrendingPersonDTO>> getTrendingYearly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(nameService.getTrendingNamesCurrentYear(limit));
    }

    @GetMapping("/trending/yearly/{year}")
    @Operation(summary = "Get highest grossing names for a specific year")
    public ResponseEntity<List<TrendingPersonDTO>> getTrendingByYear(
            @PathVariable int year,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(nameService.getTrendingNamesYearly(year, limit));
    }
}

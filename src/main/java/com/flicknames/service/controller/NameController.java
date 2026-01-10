package com.flicknames.service.controller;

import com.flicknames.service.dto.NameStatsDTO;
import com.flicknames.service.dto.PersonDTO;
import com.flicknames.service.dto.TrendingNameDTO;
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
@Tag(name = "Names", description = "First name discovery and trending APIs for baby name inspiration")
public class NameController {

    private final NameService nameService;

    @GetMapping("/trending/weekly")
    @Operation(summary = "Get trending first names from this week's box office",
               description = "Returns first names aggregated across all people, ranked by combined box office revenue")
    public ResponseEntity<List<TrendingNameDTO>> getTrendingWeekly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(nameService.getTrendingNamesWeekly(limit));
    }

    @GetMapping("/trending/yearly")
    @Operation(summary = "Get highest grossing first names this year",
               description = "Returns first names from current year movies, aggregated by total revenue")
    public ResponseEntity<List<TrendingNameDTO>> getTrendingYearly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(nameService.getTrendingNamesCurrentYear(limit));
    }

    @GetMapping("/trending/yearly/{year}")
    @Operation(summary = "Get highest grossing first names for a specific year",
               description = "Returns first names from movies released in the specified year")
    public ResponseEntity<List<TrendingNameDTO>> getTrendingByYear(
            @PathVariable int year,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(nameService.getTrendingNamesYearly(year, limit));
    }

    @GetMapping("/{firstName}/stats")
    @Operation(summary = "Get detailed statistics for a first name",
               description = "Returns aggregated stats including total box office, movie count, gender distribution, and all people with this name")
    public ResponseEntity<NameStatsDTO> getNameStats(@PathVariable String firstName) {
        return ResponseEntity.ok(nameService.getNameStats(firstName));
    }

    @GetMapping("/{firstName}/people")
    @Operation(summary = "Get all people with a specific first name",
               description = "Returns a list of all individuals in the database with the given first name")
    public ResponseEntity<List<PersonDTO>> getPeopleByName(@PathVariable String firstName) {
        return ResponseEntity.ok(nameService.getPeopleByFirstName(firstName));
    }
}

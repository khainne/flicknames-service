package com.flicknames.service.controller;

import com.flicknames.service.dto.CharacterDTO;
import com.flicknames.service.dto.NameStatsDTO;
import com.flicknames.service.dto.TrendingNameDTO;
import com.flicknames.service.service.CharacterNameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/character-names")
@RequiredArgsConstructor
@Tag(name = "Character Names", description = "Character first name discovery and trending APIs for baby name inspiration")
public class CharacterNameController {

    private final CharacterNameService characterNameService;

    @GetMapping("/trending/weekly")
    @Operation(summary = "Get trending character first names from this week's box office",
               description = "Returns character first names aggregated across all characters, ranked by combined box office revenue")
    public ResponseEntity<List<TrendingNameDTO>> getTrendingWeekly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(characterNameService.getTrendingNamesWeekly(limit));
    }

    @GetMapping("/trending/yearly")
    @Operation(summary = "Get highest grossing character first names this year",
               description = "Returns character first names from current year movies, aggregated by total revenue")
    public ResponseEntity<List<TrendingNameDTO>> getTrendingYearly(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(characterNameService.getTrendingNamesCurrentYear(limit));
    }

    @GetMapping("/trending/yearly/{year}")
    @Operation(summary = "Get highest grossing character first names for a specific year",
               description = "Returns character first names from movies released in the specified year")
    public ResponseEntity<List<TrendingNameDTO>> getTrendingByYear(
            @PathVariable int year,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(characterNameService.getTrendingNamesYearly(year, limit));
    }

    @GetMapping("/{firstName}/stats")
    @Operation(summary = "Get detailed statistics for a character first name",
               description = "Returns aggregated stats including total box office, movie count, gender distribution, and all characters with this name")
    public ResponseEntity<NameStatsDTO> getNameStats(@PathVariable String firstName) {
        return ResponseEntity.ok(characterNameService.getNameStats(firstName));
    }

    @GetMapping("/{firstName}/characters")
    @Operation(summary = "Get all characters with a specific first name",
               description = "Returns a list of all characters in the database with the given first name (e.g., all Peters: Peter Parker, Peter Quill, Peter Pan)")
    public ResponseEntity<List<CharacterDTO>> getCharactersByName(@PathVariable String firstName) {
        return ResponseEntity.ok(characterNameService.getCharactersByFirstName(firstName));
    }
}

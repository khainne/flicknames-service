package com.flicknames.service.controller;

import com.flicknames.service.service.MovieNamesService;
import com.flicknames.service.service.MovieNamesService.MovieNames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
@Tag(name = "Movie Names", description = "How common or unusual the first names in a film's credits are")
public class MovieNamesController {

    private final MovieNamesService movieNames;

    @GetMapping("/{id}/names")
    @Operation(summary = "The first names in a film's credits, with rarity tiers, screen debuts and film-level scores",
        description = "Every person credited (cast and crew) counts once. Each name gets a tier from all-time US births "
            + "(EVERYDAY: top 100, COMMON: top 1,000, UNCOMMON: 1,000+ births, RARE: under 1,000, NEVER_REGISTERED: "
            + "none) and firstSeen, its earliest release date in the catalogue as a person or character; debut is true "
            + "when that's this film. summary.variety is distinct names over people, topHeavyShare the share with a "
            + "top-20 name, unusualShare the share UNCOMMON or rarer. benchmark compares with films of the same year "
            + "(percentile: share of films below this one) and is null for films with under 20 people credited. "
            + "Characters are listed separately and don't enter the scores. 404 for an unknown film.")
    public ResponseEntity<MovieNames> names(@PathVariable long id) {
        return ResponseEntity.of(movieNames.movieNames(id));
    }
}

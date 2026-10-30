package com.flicknames.service.controller;

import com.flicknames.service.dto.CharacterWithMovieDTO;
import com.flicknames.service.service.NameCreditsService;
import com.flicknames.service.service.NameCreditsService.CastCredit;
import com.flicknames.service.service.NamePopularityService;
import com.flicknames.service.service.NamePopularityService.Popularity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Data for a name page beyond its summary: baby-name popularity in depth, and the roles behind it. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Name Page", description = "Popularity history, cast credits and character appearances for a name")
public class NamePageController {

    private static final int MAX_LIMIT = 50;

    private final NamePopularityService popularity;
    private final NameCreditsService credits;

    @GetMapping("/names/{name}/popularity")
    @Operation(summary = "A name's US baby-name popularity: every year with its rank, share of births, state ranks, "
            + "related and rising names",
        description = "Ranks are null outside the top 1,000. A name used for both sexes is shown as the more common "
            + "one. states is null when there is no state data for the name; related holds SSA counts for its "
            + "spellings, short forms and research-related names now and ten years earlier; rising holds up to eight "
            + "same-sex names sharing its first or last two letters, growing fastest over five years. 404 when the "
            + "SSA has no record of the name.")
    public ResponseEntity<Popularity> popularity(@PathVariable String name) {
        return ResponseEntity.of(popularity.popularity(name));
    }

    @GetMapping("/names/{name}/cast")
    @Operation(summary = "People with this first name in a film's cast, with the role they played",
        description = "One role per person per film, biggest-grossing films first. character is null when the "
            + "credit names no character.")
    public List<CastCredit> cast(@PathVariable String name, @RequestParam(defaultValue = "12") int limit) {
        return credits.cast(name, clamp(limit));
    }

    @GetMapping("/character-names/{name}/appearances")
    @Operation(summary = "Characters with this first name, each with the film it appears in and who played it",
        description = "Each character once, in its biggest-grossing film; biggest films first.")
    public List<CharacterWithMovieDTO> appearances(@PathVariable String name,
                                                   @RequestParam(defaultValue = "24") int limit) {
        return credits.appearances(name, clamp(limit));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}

package com.flicknames.service.controller;

import com.flicknames.service.dto.ProfessionCountDTO;
import com.flicknames.service.dto.ProfessionSummaryDTO;
import com.flicknames.service.service.ProfessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/professions")
@RequiredArgsConstructor
@Tag(name = "Professions", description = "Query movie industry professions (Actor, Director, Gaffer, etc.)")
public class ProfessionController {

    private final ProfessionService professionService;

    @GetMapping
    @Operation(summary = "Get all professions",
               description = "Returns all professions/jobs found in movie credits with statistics")
    public ResponseEntity<List<ProfessionSummaryDTO>> getAllProfessions() {
        return ResponseEntity.ok(professionService.getAllProfessions());
    }

    @GetMapping("/for-name/{firstName}")
    @Operation(summary = "Get profession breakdown for a name",
               description = "Returns array of professions for people with this first name, with counts for word cloud visualization")
    public ResponseEntity<List<ProfessionCountDTO>> getProfessionsForName(
            @PathVariable String firstName) {
        return ResponseEntity.ok(professionService.getProfessionsByFirstName(firstName));
    }

    @GetMapping("/{profession}/names/count")
    @Operation(summary = "Count unique names in a profession",
               description = "Returns how many unique first names exist in a given profession")
    public ResponseEntity<Long> getUniqueNameCount(
            @PathVariable String profession) {
        return ResponseEntity.ok(professionService.getUniqueNameCountByProfession(profession));
    }
}

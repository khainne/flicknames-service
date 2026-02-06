package com.flicknames.service.controller;

import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.ScreenCharacterRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/character-names/review")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Character Review", description = "Manual review interface for character name quality control")
public class CharacterReviewController {

    private final ScreenCharacterRepository characterRepository;

    @GetMapping
    @Operation(summary = "Get characters for review",
               description = "Returns paginated list of characters filtered by review status. " +
                            "Use for manual quality control interface.")
    public ResponseEntity<Map<String, Object>> getCharactersForReview(
            @RequestParam(defaultValue = "PENDING_REVIEW") ScreenCharacter.ReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit) {

        Pageable pageable = PageRequest.of(page, limit, Sort.by("id").ascending());
        Page<ScreenCharacter> charactersPage = characterRepository.findByReviewStatus(status, pageable);

        List<Map<String, Object>> characters = charactersPage.getContent().stream()
            .map(this::characterToMap)
            .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("characters", characters);
        response.put("currentPage", page);
        response.put("totalPages", charactersPage.getTotalPages());
        response.put("totalElements", charactersPage.getTotalElements());
        response.put("hasNext", charactersPage.hasNext());
        response.put("hasPrevious", charactersPage.hasPrevious());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/flag")
    @Operation(summary = "Flag a character name",
               description = "Mark a character as having an incorrect parse or containing no first name")
    public ResponseEntity<Map<String, Object>> flagCharacter(
            @PathVariable Long id,
            @RequestParam ScreenCharacter.ReviewStatus flagType,
            @RequestParam(required = false) String notes) {

        return characterRepository.findById(id)
            .map(character -> {
                character.setReviewStatus(flagType);
                character.setReviewNotes(notes);
                character.setReviewedAt(LocalDateTime.now());
                characterRepository.save(character);

                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("message", "Character flagged as " + flagType);
                response.put("character", characterToMap(character));
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a character name",
               description = "Mark a character as correctly parsed and approved")
    public ResponseEntity<Map<String, Object>> approveCharacter(
            @PathVariable Long id,
            @RequestParam(required = false) String notes) {

        return characterRepository.findById(id)
            .map(character -> {
                character.setReviewStatus(ScreenCharacter.ReviewStatus.APPROVED);
                character.setReviewNotes(notes);
                character.setReviewedAt(LocalDateTime.now());
                characterRepository.save(character);

                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("message", "Character approved");
                response.put("character", characterToMap(character));
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    @Operation(summary = "Get review statistics",
               description = "Returns counts for each review status")
    public ResponseEntity<Map<String, Object>> getReviewStats() {
        Map<String, Object> stats = new HashMap<>();

        for (ScreenCharacter.ReviewStatus status : ScreenCharacter.ReviewStatus.values()) {
            long count = characterRepository.countByReviewStatus(status);
            stats.put(status.toString(), count);
        }

        stats.put("status", "success");
        return ResponseEntity.ok(stats);
    }

    private Map<String, Object> characterToMap(ScreenCharacter character) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", character.getId());
        map.put("fullName", character.getFullName());
        map.put("firstName", character.getFirstName());
        map.put("lastName", character.getLastName());
        map.put("nameType", character.getNameType().toString());
        map.put("reviewStatus", character.getReviewStatus().toString());
        map.put("reviewNotes", character.getReviewNotes());
        map.put("reviewedAt", character.getReviewedAt());
        map.put("manuallyVerified", character.isManuallyVerified());

        // Add movie information for context
        if (character.getCredits() != null && !character.getCredits().isEmpty()) {
            // Get the highest revenue movie for better context
            var topMovie = character.getCredits().stream()
                .filter(credit -> credit.getMovie() != null)
                .max((c1, c2) -> {
                    java.math.BigDecimal rev1 = c1.getMovie().getRevenue();
                    java.math.BigDecimal rev2 = c2.getMovie().getRevenue();
                    if (rev1 == null && rev2 == null) return 0;
                    if (rev1 == null) return -1;
                    if (rev2 == null) return 1;
                    return rev1.compareTo(rev2);
                })
                .map(credit -> {
                    Map<String, Object> movieInfo = new HashMap<>();
                    movieInfo.put("title", credit.getMovie().getTitle());
                    movieInfo.put("year", credit.getMovie().getReleaseDate() != null ?
                        credit.getMovie().getReleaseDate().getYear() : null);
                    movieInfo.put("revenue", credit.getMovie().getRevenue());
                    return movieInfo;
                });

            map.put("movie", topMovie.orElse(null));
            map.put("movieCount", character.getCredits().size());
        } else {
            map.put("movie", null);
            map.put("movieCount", 0);
        }

        return map;
    }
}

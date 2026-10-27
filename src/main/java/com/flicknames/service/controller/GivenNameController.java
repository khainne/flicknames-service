package com.flicknames.service.controller;

import com.flicknames.service.entity.GivenNameLink.Source;
import com.flicknames.service.entity.GivenNameLink.Status;
import com.flicknames.service.entity.GivenNameLink.Type;
import com.flicknames.service.service.GivenNameCatalog;
import com.flicknames.service.service.GivenNameCatalog.LinkView;
import com.flicknames.service.service.GivenNameCatalog.RebuildResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Given names and the links between them (diminutives, spelling variants, cognates, gendered and compound
 * forms), for review. Not used by the public site yet. Writes need X-Admin-Key.
 */
@RestController
@RequestMapping("/api/v1/admin/given-names")
@RequiredArgsConstructor
@Tag(name = "Given Names", description = "Given-name catalog and the links between names")
public class GivenNameController {

    private final GivenNameCatalog catalog;

    public record Decision(String from, String to, Type type, Status status) {}

    @GetMapping("/summary")
    @Operation(summary = "Counts of names by quality and of links by source, type and status")
    public Map<String, Object> summary() {
        return catalog.summary();
    }

    @GetMapping("/links")
    @Operation(summary = "Links for review, filtered by status, type and source (at most 1000)")
    public List<LinkView> links(@RequestParam(required = false) Status status,
                                @RequestParam(required = false) Type type,
                                @RequestParam(required = false) Source source,
                                @RequestParam(defaultValue = "100") int limit) {
        return catalog.links(status, type, source, limit);
    }

    @GetMapping("/{spelling}")
    @Operation(summary = "A spelling with its counts, the spellings that fold to the same key, and its links")
    public ResponseEntity<?> name(@PathVariable String spelling) {
        return catalog.name(spelling).<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Refresh names and counts from every source and rebuild the research and compound links",
        description = "Admin decisions are kept. Also runs nightly in production (given-names.refresh.enabled).")
    public RebuildResult rebuild() {
        return catalog.rebuild();
    }

    @PostMapping("/links")
    @Operation(summary = "Accept or reject a link; the decision overrides the other sources and survives rebuilds",
        description = "Body: {\"from\": \"Nick\", \"to\": \"Nicholas\", \"type\": \"DIMINUTIVE_OF\", \"status\": \"ACCEPTED\"}.")
    public ResponseEntity<?> decide(@RequestBody Decision decision) {
        try {
            return ResponseEntity.ok(catalog.decide(decision.from(), decision.to(), decision.type(), decision.status()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}

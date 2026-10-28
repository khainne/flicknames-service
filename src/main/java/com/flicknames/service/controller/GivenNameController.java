package com.flicknames.service.controller;

import com.flicknames.service.entity.GivenNameLink.Source;
import com.flicknames.service.entity.GivenNameLink.Status;
import com.flicknames.service.entity.GivenNameLink.Type;
import com.flicknames.service.entity.DiminutiveUsage.Usage;
import com.flicknames.service.service.GivenNameCatalog;
import com.flicknames.service.service.NameFamilyService;
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
    private final NameFamilyService families;

    public record Decision(String from, String to, Type type, Status status) {}

    public record UsageDecision(String shortForm, String fullForm, Usage usage) {}

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

    @PostMapping("/families/classify-short-forms")
    @Operation(summary = "Ask Claude whether accepted short forms are nicknames in use or independent names today",
        description = "Only pairs without a decision; at most limit of them, 50 per request (Haiku, cents in all). "
            + "redo=true first drops the model's earlier decisions; an admin's are kept.")
    public ResponseEntity<?> classifyShortForms(@RequestParam(defaultValue = "2000") int limit,
                                                @RequestParam(defaultValue = "false") boolean redo) {
        try {
            return ResponseEntity.ok(families.classifyShortForms(limit, redo));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/families/usage")
    @Operation(summary = "Short-form usage decisions (NICKNAME or INDEPENDENT), by the model or an admin")
    public List<Map<String, Object>> usages(@RequestParam(required = false) Usage usage,
                                            @RequestParam(defaultValue = "200") int limit) {
        return families.usages(usage, limit);
    }

    @PostMapping("/families/usage")
    @Operation(summary = "Decide whether a short form is a nickname in use or an independent name; replaces the model's",
        description = "Body: {\"shortForm\": \"Liam\", \"fullForm\": \"William\", \"usage\": \"INDEPENDENT\"}. "
            + "Takes effect at the next family rebuild.")
    public ResponseEntity<?> decideUsage(@RequestBody UsageDecision decision) {
        if (decision == null || decision.shortForm() == null || decision.fullForm() == null || decision.usage() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "shortForm, fullForm and usage are required"));
        }
        families.decideUsage(decision.shortForm(), decision.fullForm(), decision.usage());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/families/rebuild")
    @Operation(summary = "Rebuild name families and their totals from the accepted links (also nightly)")
    public NameFamilyService.RebuildResult rebuildFamilies() {
        return families.rebuild();
    }

    @GetMapping("/families")
    @Operation(summary = "The largest families by people and characters")
    public List<Map<String, Object>> largestFamilies(@RequestParam(defaultValue = "50") int limit) {
        return families.largest(limit);
    }

    @GetMapping("/families/of/{spelling}")
    @Operation(summary = "The families a spelling belongs to, with their members and totals")
    public List<Map<String, Object>> familiesOf(@PathVariable String spelling) {
        return families.familiesOf(spelling);
    }
}

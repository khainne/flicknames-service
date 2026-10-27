package com.flicknames.service.controller;

import com.flicknames.service.service.CharacterNameModelAutoPass;
import com.flicknames.service.service.CharacterNameModelService;
import com.flicknames.service.service.CharacterNameModelService.ModelUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The model pass for character credits the parser is unsure of: pilot, batch submit, collect, stage.
 * Every call that can spend money is a POST, so it needs X-Admin-Key (AdminApiKeyFilter).
 */
@RestController
@RequestMapping("/api/v1/admin/character-name-model")
@RequiredArgsConstructor
@Tag(name = "Character Name Model", description = "Claude decides the first name in low-confidence character credits")
public class CharacterNameModelController {

    private final CharacterNameModelService modelService;
    private final CharacterNameModelAutoPass autoPass;

    public record PilotRequest(List<String> credits) {}

    @PostMapping("/pilot")
    @Operation(summary = "Decide up to 2000 raw credits now, without storing anything",
        description = "Same prompt and context as a batch, at the standard price. For measuring the model on "
            + "labelled credits before submitting a batch.")
    public ResponseEntity<?> pilot(@RequestBody PilotRequest request) {
        return call(() -> modelService.pilot(request == null || request.credits() == null ? List.of() : request.credits()));
    }

    @PostMapping("/batches")
    @Operation(summary = "Send staged low-confidence parses without a decision as one Message Batch",
        description = "Rows with source PARSER and confidence below maxConfidence (pass 1.01 for all), not manually "
            + "verified, at most limit of them. skipCertain (default true) leaves parses a certainty rule covers to the "
            + "parser (self credits, numbered extras, clean First Last). preview=true only reports counts and the "
            + "estimated cost. Refused (409) while another batch is open.")
    public ResponseEntity<?> submit(@RequestParam(defaultValue = "0.7") double maxConfidence,
                                    @RequestParam(defaultValue = "100000") int limit,
                                    @RequestParam(defaultValue = "true") boolean skipCertain,
                                    @RequestParam(defaultValue = "false") boolean preview) {
        return call(() -> modelService.submit(maxConfidence, limit, skipCertain, preview));
    }

    @PostMapping("/batches/{batchId}/collect")
    @Operation(summary = "Refresh a batch's status and store its answers once it has ended")
    public ResponseEntity<?> collect(@PathVariable String batchId) {
        return call(() -> modelService.collect(batchId));
    }

    @PostMapping("/stage")
    @Operation(summary = "Write collected decisions into the re-parse staging table",
        description = "Decisions that differ from the staged parse become source MODEL rows; apply them with "
            + "POST /api/v1/admin/character-name-reparse/apply (revert works as usual). An answer that is an age/kin "
            + "prefix or a title (Young, Lady, King) falls back to the parser. recheck=true also goes over "
            + "decisions staged before, so a rule change reaches applied rows.")
    public ResponseEntity<?> stage(@RequestParam(defaultValue = "false") boolean recheck) {
        return call(() -> modelService.stage(recheck));
    }

    @PostMapping("/auto-pass")
    @Operation(summary = "Take the next step of the scheduled model pass now",
        description = "What the hourly schedule does (character-name-model.schedule.enabled): collect an open batch "
            + "and stage and apply its answers; or stage new characters with the parser; or, at most once per "
            + "submit-every-hours, send the staged credits without a decision or a certain parse (at most max-items). "
            + "Returns the step taken.")
    public ResponseEntity<?> autoPass() {
        return call(autoPass::tick);
    }

    @GetMapping("/summary")
    @Operation(summary = "Decision counts, agreement with the parser, sample disagreements and batch costs")
    public ResponseEntity<?> summary() {
        return ResponseEntity.ok(modelService.summary());
    }

    private static ResponseEntity<?> call(Supplier<?> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (ModelUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}

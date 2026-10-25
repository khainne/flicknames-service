package com.flicknames.service.controller;

import com.flicknames.service.service.CharacterNameReparseService;
import com.flicknames.service.service.CharacterNameReparseService.JobStatus;
import com.flicknames.service.service.CharacterNameReparseService.ReparseConflictException;
import com.flicknames.service.service.CharacterNameReparseService.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Re-parse of stored character names with parser v2: dry run, report, apply, revert. POSTs need
 * X-Admin-Key via AdminApiKeyFilter; the GETs only read name statistics.
 */
@RestController
@RequestMapping("/api/v1/admin/character-name-reparse")
@RequiredArgsConstructor
@Tag(name = "Character Name Re-parse", description = "Stage, review, apply and revert a v2 re-parse of character names")
public class CharacterNameReparseController {

    private final CharacterNameReparseService reparseService;

    @PostMapping("/dry-run")
    @Operation(summary = "Stage a v2 re-parse of every character (writes only character_reparse_rows)",
        description = "Runs in the background; poll GET /status. Refused (409) while the staged run is applied, "
            + "because its rows are the backup, unless discardAppliedBackup=true.")
    public ResponseEntity<?> dryRun(@RequestParam(defaultValue = "false") boolean discardAppliedBackup) {
        return started(() -> reparseService.startDryRun(discardAppliedBackup));
    }

    @PostMapping("/apply")
    @Operation(summary = "Write the staged results onto characters, then apply decisive labels",
        description = "Rows edited since the dry run are skipped and counted. Runs in the background.")
    public ResponseEntity<?> apply() {
        return started(reparseService::startApply);
    }

    @PostMapping("/revert")
    @Operation(summary = "Restore the values recorded by the dry run on applied rows",
        description = "Rows edited since the apply are skipped and counted. Labels stay applied. Runs in the background.")
    public ResponseEntity<?> revert() {
        return started(reparseService::startRevert);
    }

    @GetMapping("/status")
    @Operation(summary = "The current or last re-parse step since the service started")
    public ResponseEntity<?> status() {
        return reparseService.status().<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.ok(Map.of("state", "IDLE")));
    }

    @GetMapping("/report")
    @Operation(summary = "Compare the staged run with the values before it",
        description = "Summary counts, the most common removed and changed first names, and the top names per "
            + "release year (and over all years) before and after, by summed movie revenue over credits.")
    public Report report(@RequestParam(defaultValue = "100") int top,
                         @RequestParam(required = false) Integer fromYear,
                         @RequestParam(required = false) Integer toYear) {
        return reparseService.report(top, fromYear, toYear);
    }

    private interface Starter {
        JobStatus start();
    }

    private static ResponseEntity<?> started(Starter starter) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(starter.start());
        } catch (ReparseConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}

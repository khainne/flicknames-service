package com.flicknames.service.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.entity.CharacterNameLabel.Verdict;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.service.CharacterNameLabelQueueService;
import com.flicknames.service.service.CharacterNameLabelQueueService.QueueFilter;
import com.flicknames.service.service.CharacterNameLabelQueueService.QueuePage;
import com.flicknames.service.service.CharacterNameLabelService;
import com.flicknames.service.service.CharacterNameLabelService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Human labels for character first names. GETs are public (labels are not sensitive); POST and
 * DELETE need X-Admin-Key via AdminApiKeyFilter.
 */
@RestController
@RequestMapping("/api/v1/admin/character-name-labels")
@RequiredArgsConstructor
@Tag(name = "Character Name Labels", description = "Human first-name labels: labeling queue, overrides, golden-set export")
public class CharacterNameLabelController {

    private final CharacterNameLabelService labelService;
    private final CharacterNameLabelQueueService queueService;
    private final ObjectMapper objectMapper;

    @GetMapping("/queue")
    @Operation(summary = "Next unlabeled character strings, most impactful first",
        description = "Distinct characters.full_name values with no label (SKIPPED counts as labeled), ranked by "
            + "the summed revenue of the distinct movies they appear in. Each item carries the stored parse, "
            + "up to 3 example movies with the credited actor, and counts. Optional filters: firstName "
            + "(repeatable or comma-separated; stored first_name, case-insensitive), hasFirstName, nameType "
            + "(repeatable or comma-separated), q (substring of the raw string). The ranking is cached per "
            + "filter for 30 minutes (labels are always subtracted live); refresh=true recomputes it.")
    public QueuePage queue(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(name = "firstName", required = false) List<String> firstNames,
            @RequestParam(required = false) Boolean hasFirstName,
            @RequestParam(name = "nameType", required = false) List<String> nameTypes,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return queueService.next(new QueueFilter(splitCsv(firstNames), hasFirstName, parseNameTypes(nameTypes), search),
            offset, limit, refresh);
    }

    @GetMapping
    @Operation(summary = "Labels, most recently written first", description = "Paged; optional verdict filter.")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Verdict verdict) {
        Page<LabelDTO> labels = labelService.list(verdict, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("labels", labels.getContent());
        body.put("page", labels.getNumber());
        body.put("size", labels.getSize());
        body.put("totalElements", labels.getTotalElements());
        body.put("totalPages", labels.getTotalPages());
        return body;
    }

    @GetMapping("/by-name")
    @Operation(summary = "The label for one exact raw string", description = "404 if the string has no label.")
    public ResponseEntity<LabelDTO> byName(@RequestParam String fullName) {
        return ResponseEntity.of(labelService.get(fullName));
    }

    @GetMapping("/stats")
    @Operation(summary = "Label counts by verdict",
        description = "Totals, plus counts for labels written since `since` (ISO-8601 instant or offset date-time, "
            + "e.g. the labeler's local midnight; default: start of today in server time), and how many decisive "
            + "labels have not been applied to characters since they last changed.")
    public LabelStats stats(@RequestParam(required = false) String since) {
        return labelService.stats(parseSince(since));
    }

    @GetMapping(value = "/export.jsonl", produces = "application/x-ndjson")
    @Operation(summary = "Labels as golden-set JSONL",
        description = "One line per label: {\"input\": fullName, \"expected\": firstName or null, \"source\": \"label\", "
            + "\"note\": ...}; note only when present, `set` only when the set parameter is given. NAME and "
            + "NOT_A_NAME labels only unless includeSkipped=true (skipped lines then carry \"skipped\": true).")
    public ResponseEntity<String> export(
            @RequestParam(defaultValue = "false") boolean includeSkipped,
            @RequestParam(required = false) String set) throws JsonProcessingException {
        StringBuilder out = new StringBuilder();
        for (LabelDTO label : labelService.exportable(includeSkipped)) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("input", label.fullName());
            if (set != null && !set.isBlank()) {
                line.put("set", set.trim());
            }
            line.put("expected", label.verdict() == Verdict.NAME ? label.firstName() : null);
            line.put("source", "label");
            if (label.note() != null) {
                line.put("note", label.note());
            }
            if (label.verdict() == Verdict.SKIPPED) {
                line.put("skipped", true);
            }
            out.append(objectMapper.writeValueAsString(line)).append('\n');
        }
        return ResponseEntity.ok()
            .contentType(new MediaType("application", "x-ndjson", java.nio.charset.StandardCharsets.UTF_8))
            .header("Content-Disposition", "inline; filename=\"name-labels.jsonl\"")
            .body(out.toString());
    }

    @PostMapping
    @Operation(summary = "Create or replace the label for a raw string",
        description = "Body: {fullName, verdict: NAME|NOT_A_NAME|SKIPPED, firstName (required for NAME, ignored "
            + "otherwise), note?, labeledBy?}. Upserts by exact fullName. Does NOT change the characters table; "
            + "use POST /apply for that. 201 when created, 200 when replaced.")
    public ResponseEntity<SaveResult> save(@RequestBody LabelRequest request) {
        SaveResult result = labelService.save(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PostMapping("/bulk")
    @Operation(summary = "Create or replace many labels", description = "Body: array of label objects (max 1000). All or nothing.")
    public List<SaveResult> saveAll(@RequestBody List<LabelRequest> requests) {
        return labelService.saveAll(requests);
    }

    @DeleteMapping
    @Operation(summary = "Delete the label for a raw string (undo)",
        description = "Removes the label so the string returns to the queue. Characters already updated by "
            + "/apply are NOT restored; they keep the applied values until re-labeled and re-applied.")
    public ResponseEntity<LabelDTO> delete(@RequestParam String fullName) {
        return ResponseEntity.of(labelService.delete(fullName));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a label by id", description = "Same as DELETE ?fullName=; does not restore characters.")
    public ResponseEntity<LabelDTO> deleteById(@PathVariable Long id) {
        return ResponseEntity.of(labelService.delete(id));
    }

    @PostMapping("/apply")
    @Operation(summary = "Write labels into the characters table (dry run by default)",
        description = "For every NAME / NOT_A_NAME label (or just fullName=), sets first_name on characters with "
            + "exactly that full_name, fixes name_type, clears last_name when it only repeated the first name or "
            + "the string is not a name, and marks the rows manually_verified so migrations keep them. "
            + "dryRun=true (default) writes nothing and reports what would change; pass dryRun=false to write. "
            + "Idempotent. Deleting a label later does not undo this.")
    public ApplyReport apply(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) String fullName) {
        return labelService.apply(dryRun, fullName);
    }

    @ExceptionHandler(LabelValidationException.class)
    public ResponseEntity<Map<String, String>> badRequest(LabelValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("status", "error", "message", "That label was written concurrently; retry"));
    }

    private static List<String> splitCsv(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .flatMap(v -> Arrays.stream(v.split(",")))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    }

    private static List<NameType> parseNameTypes(List<String> values) {
        List<NameType> types = new ArrayList<>();
        for (String v : splitCsv(values)) {
            try {
                types.add(NameType.valueOf(v.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new LabelValidationException("unknown nameType '" + v + "'; expected one of " + Arrays.toString(NameType.values()));
            }
        }
        return types;
    }

    private static LocalDateTime parseSince(String since) {
        if (since == null || since.isBlank()) {
            return LocalDate.now().atStartOfDay();
        }
        try {
            return OffsetDateTime.parse(since.trim()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(since.trim());
            } catch (DateTimeParseException e2) {
                throw new LabelValidationException("since must be an ISO-8601 date-time, e.g. 2026-10-25T04:00:00Z");
            }
        }
    }
}

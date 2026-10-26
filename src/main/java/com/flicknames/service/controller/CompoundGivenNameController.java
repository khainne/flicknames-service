package com.flicknames.service.controller;

import com.flicknames.service.entity.CompoundGivenName;
import com.flicknames.service.entity.CompoundGivenName.Source;
import com.flicknames.service.service.CompoundGivenNames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Two-word given names used when splitting people's names. Changes apply to people imported from then on;
 * POST /api/v1/admin/repair/person-names re-splits the people already stored. Writes need X-Admin-Key.
 */
@RestController
@RequestMapping("/api/v1/admin/compound-given-names")
@RequiredArgsConstructor
@Tag(name = "Compound Given Names", description = "Two-word given names (\"Juan Carlos\") kept whole as a first name")
public class CompoundGivenNameController {

    private final CompoundGivenNames compoundGivenNames;

    @GetMapping
    @Operation(summary = "All compound given names, enabled or not")
    public List<CompoundGivenName> all() {
        return compoundGivenNames.all();
    }

    @PostMapping
    @Operation(summary = "Add two-word given names, or re-enable disabled ones",
        description = "Body: a JSON array of names such as [\"Juan Carlos\"]. Entries not of exactly two words are ignored.")
    public List<CompoundGivenName> add(@RequestBody List<String> names) {
        return compoundGivenNames.add(names, Source.ADMIN);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Disable a compound given name (kept, so the startup seed does not add it back)")
    public ResponseEntity<Void> disable(@PathVariable String name) {
        return compoundGivenNames.disable(name) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

package com.flicknames.service.controller;

import com.flicknames.service.service.NameFamilyService;
import com.flicknames.service.service.NameFamilyService.FamilyPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/names")
@RequiredArgsConstructor
@Tag(name = "Name Families", description = "Spellings of a name and the families it belongs to")
public class NameFamilyController {

    private final NameFamilyService families;

    @GetMapping("/{name}/family")
    @Operation(summary = "A name's spellings and its families (full form, spellings and short forms in use)",
        description = "Spellings that differ only by accents or case are one name, listed most common first. Each "
            + "family's totals count distinct people, characters and movies over its members that are not shared with "
            + "another family; shared members are listed with shared=true. Empty families when the name has none.")
    public FamilyPage family(@PathVariable String name) {
        return families.page(name);
    }
}

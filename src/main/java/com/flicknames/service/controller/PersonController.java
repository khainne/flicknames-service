package com.flicknames.service.controller;

import com.flicknames.service.dto.MovieDTO;
import com.flicknames.service.dto.PersonDTO;
import com.flicknames.service.dto.PersonStatsDTO;
import com.flicknames.service.service.NameService;
import com.flicknames.service.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/people")
@RequiredArgsConstructor
@Tag(name = "People", description = "Person and name details APIs")
public class PersonController {

    private final PersonService personService;
    private final NameService nameService;

    @GetMapping("/{id}")
    @Operation(summary = "Get person details by ID")
    public ResponseEntity<PersonDTO> getPersonById(@PathVariable Long id) {
        return ResponseEntity.ok(personService.getPersonById(id));
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "Get statistics for a person")
    public ResponseEntity<PersonStatsDTO> getPersonStats(@PathVariable Long id) {
        return ResponseEntity.ok(personService.getPersonStats(id));
    }

    @GetMapping("/{id}/movies")
    @Operation(summary = "Get all movies for a person")
    public ResponseEntity<List<MovieDTO>> getMoviesForPerson(@PathVariable Long id) {
        return ResponseEntity.ok(personService.getMoviesForPerson(id));
    }

    @GetMapping("/search")
    @Operation(summary = "Search for people by name")
    public ResponseEntity<Page<PersonDTO>> searchPeople(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(personService.searchPeople(q, PageRequest.of(page, size)));
    }
}

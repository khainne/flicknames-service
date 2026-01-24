package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Search result wrapper for name searches
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameSearchResultDTO {
    private String name;
    private Integer totalOccurrences;
    private NameSourcesDTO sources;
    private List<ProfessionCountDTO> professions;
    private List<Object> results;  // Can be CharacterWithMovieDTO or PersonWithMoviesDTO
}

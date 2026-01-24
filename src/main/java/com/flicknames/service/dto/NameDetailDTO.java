package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Detailed name information including all characters, people, and movies
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameDetailDTO {
    private String firstName;
    private NameStatisticsDTO statistics;

    // Array of professions with full details
    private List<ProfessionDetailDTO> professions;

    // Characters with this name
    private List<CharacterWithMovieDTO> characters;

    // Top movies featuring this name
    private List<MovieCardDTO> topMovies;

    // Related/similar names
    private List<String> relatedNames;
}

package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lightweight name DTO for lists and discovery
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameCardDTO {
    private String firstName;
    private Integer totalOccurrences;
    private Integer characterCount;
    private Integer peopleCount;

    // Array of professions for word cloud
    private List<ProfessionCountDTO> professions;

    private Integer movieCount;
    private List<MovieCardDTO> featuredMovies;
    private String primaryImageUrl;  // Poster from most popular movie
    private Boolean trending;
    private Integer trendRank;
}

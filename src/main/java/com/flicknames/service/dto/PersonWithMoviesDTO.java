package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Person with their professions and notable movies
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonWithMoviesDTO {
    private PersonCardDTO person;
    private List<String> professions;  // Array - person can have multiple professions
    private Integer movieCount;
    private List<MovieCardDTO> notableMovies;
}

package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Character with movie and actor context
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterWithMovieDTO {
    private Long characterId;
    private String firstName;
    private String fullName;
    private String gender;
    private MovieCardDTO movie;
    private PersonCardDTO actor;
    private Integer castOrder;
}

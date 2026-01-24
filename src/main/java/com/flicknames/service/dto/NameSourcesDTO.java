package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Breakdown of name sources (characters vs real people)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameSourcesDTO {
    private Integer characters;
    private Integer people;
}

package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Detailed profession information including people with this profession
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfessionDetailDTO {
    private String name;
    private String department;
    private Integer count;
    private Double percentage;
    private List<PersonWithMoviesDTO> people;
}

package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonStatsDTO {
    private PersonDTO person;
    private BigDecimal totalBoxOffice;
    private Integer totalMovies;
    private Map<String, Integer> jobCounts; // e.g., {"Actor": 15, "Producer": 3}
    private Integer castRoles;
    private Integer crewRoles;
}

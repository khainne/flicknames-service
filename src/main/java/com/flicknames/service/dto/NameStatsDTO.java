package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameStatsDTO {
    private String name;                        // First name
    private BigDecimal totalBoxOffice;          // Total revenue across all people with this name
    private Integer totalMovies;                // Total distinct movies
    private Integer peopleCount;                // Number of different people with this name
    private Map<String, Integer> genderDistribution;  // e.g., {"Female": 8, "Male": 2}
    private Map<String, Integer> roleDistribution;    // e.g., {"Actor": 45, "Director": 12}
    private List<PersonDTO> people;             // All people with this name
    private List<MovieDTO> topMovies;           // Highest grossing movies featuring this name
}

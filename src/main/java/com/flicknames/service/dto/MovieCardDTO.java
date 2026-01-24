package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lightweight movie DTO for lists and cards
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieCardDTO {
    private Long id;
    private String title;
    private LocalDate releaseDate;
    private Integer releaseYear;
    private String posterPath;
    private String backdropPath;
    private Double voteAverage;
    private Integer voteCount;
    private BigDecimal revenue;
    private Integer runtime;
    private String status;
}

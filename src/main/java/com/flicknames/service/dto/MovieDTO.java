package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieDTO {
    private Long id;
    private String title;
    private LocalDate releaseDate;
    private BigDecimal budget;
    private BigDecimal revenue;
    private Integer runtime;
    private String overview;
    private String posterPath;
    private String backdropPath;
    private String originalLanguage;
    private Double voteAverage;
    private Integer voteCount;
    private String status;
}

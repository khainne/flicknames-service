package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingPersonDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String fullName;
    private String gender;
    private String profilePath;
    private BigDecimal totalRevenue;
    private Integer movieCount;
    private List<MovieDTO> recentMovies;
}

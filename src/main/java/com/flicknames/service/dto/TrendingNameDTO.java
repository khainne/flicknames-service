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
public class TrendingNameDTO {
    private String name;                    // First name (e.g., "Emma")
    private BigDecimal totalRevenue;        // Combined revenue across all people with this name
    private Integer movieCount;             // Total distinct movies
    private Integer peopleCount;            // How many different people have this name
    private String primaryGender;           // Most common gender for this name
    private List<PersonDTO> topPeople;      // Notable people with this name (top 5 by revenue)
    private List<MovieDTO> recentMovies;    // Recent movies featuring this name
}

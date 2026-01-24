package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Statistics about a name's usage across movies
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameStatisticsDTO {
    private Integer totalCharacters;
    private Integer totalPeople;
    private Integer totalMovies;
    private List<Integer> appearsInYears;
    private Integer popularityRank;
}

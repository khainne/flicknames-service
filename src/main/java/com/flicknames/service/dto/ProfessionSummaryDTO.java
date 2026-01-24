package com.flicknames.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of a profession for listing all professions
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfessionSummaryDTO {
    private String name;
    private String department;
    private Integer uniqueNames;
    private Integer totalPeople;
    private String popularity;  // "high", "medium", "low"
}

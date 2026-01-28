package com.flicknames.service.research.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for names that need research
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameToResearchDTO {
    private String name;
    private String sex;
    private Long totalCount; // Total occurrences across all years
    private Integer rank2023; // Most recent year rank (if available)
}

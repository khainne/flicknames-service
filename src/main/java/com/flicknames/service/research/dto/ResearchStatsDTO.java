package com.flicknames.service.research.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics about research coverage
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchStatsDTO {
    private long totalResearch;
    private long approvedResearch;
    private long pendingResearch;
    private long rejectedResearch;
    private long totalNames; // Total unique names in SSA data
    private double coveragePercentage;
}

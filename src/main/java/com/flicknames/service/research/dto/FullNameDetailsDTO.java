package com.flicknames.service.research.dto;

import com.flicknames.service.dto.PersonCardDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Complete name information combining research, SSA stats, and namesakes
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullNameDetailsDTO {
    private String name;

    // Name research data (etymology, meaning, etc.)
    private NameResearchDTO research;

    // SSA statistics
    private SsaStatsDTO ssaStats;

    // Famous people with this name (namesakes)
    private List<PersonCardDTO> namesakes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SsaStatsDTO {
        private String sex;
        private Long totalCount;
        private Integer peakYear;
        private Long peakCount;
        private Integer firstYear;
        private Integer lastYear;
        private List<YearlyStatDTO> recentYears;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class YearlyStatDTO {
        private Integer year;
        private Long count;
        private Integer rank;
    }
}

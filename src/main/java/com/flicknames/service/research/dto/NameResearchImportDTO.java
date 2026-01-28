package com.flicknames.service.research.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for importing name research data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameResearchImportDTO {
    private String name;
    private String etymology;
    private String meaning;
    private String rootLanguage;
    private String history;
    private PronunciationImportDTO pronunciation;
    private String genderClassification;
    private List<CulturalUsageImportDTO> culturalUsages;
    private List<RelatedNameImportDTO> relatedNames;
    private List<String> categories;
    private Integer confidenceScore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PronunciationImportDTO {
        private String ipa;
        private String respelling;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CulturalUsageImportDTO {
        private String culture;
        private String culturalMeaning;
        private Integer prevalence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedNameImportDTO {
        private String name;
        private String type;
    }
}

package com.flicknames.service.research.dto;

import com.flicknames.service.research.entity.NameResearch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for name research data (public API - only returns APPROVED research)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameResearchDTO {
    private Long id;
    private String name;
    private String etymology;
    private String meaning;
    private String rootLanguage;
    private String history;
    private PronunciationDTO pronunciation;
    private String genderClassification;
    private Integer confidenceScore;
    private List<CulturalUsageDTO> culturalUsages;
    private List<RelatedNameDTO> relatedNames;
    private List<String> categories;
    private List<String> sources;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PronunciationDTO {
        private String ipa;
        private String respelling;
    }
}

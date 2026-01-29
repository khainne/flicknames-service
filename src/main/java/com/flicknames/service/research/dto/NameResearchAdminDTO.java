package com.flicknames.service.research.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for admin view of name research (includes status and review notes)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameResearchAdminDTO {
    private Long id;
    private String name;
    private String etymology;
    private String meaning;
    private String rootLanguage;
    private String history;
    private String contemporaryContext;
    private NameResearchDTO.PronunciationDTO pronunciation;
    private String genderClassification;
    private Integer confidenceScore;
    private String status;
    private String reviewNotes;
    private List<CulturalUsageDTO> culturalUsages;
    private List<RelatedNameDTO> relatedNames;
    private List<String> categories;
    private List<String> sources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

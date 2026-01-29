package com.flicknames.service.research.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.repository.SsaNameRepository;
import com.flicknames.service.research.dto.*;
import com.flicknames.service.research.entity.*;
import com.flicknames.service.research.repository.NameResearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NameResearchService {

    private final NameResearchRepository nameResearchRepository;
    private final SsaNameRepository ssaNameRepository;
    private final ObjectMapper objectMapper;

    /**
     * Get approved research for a name (public API)
     */
    public Optional<NameResearchDTO> getApprovedResearch(String name) {
        return nameResearchRepository.findByNameIgnoreCase(name)
            .filter(research -> research.getStatus() == NameResearch.ResearchStatus.APPROVED)
            .map(this::toDTO);
    }

    /**
     * Get research by ID (admin view)
     */
    public Optional<NameResearchAdminDTO> getResearchById(Long id) {
        return nameResearchRepository.findById(id)
            .map(this::toAdminDTO);
    }

    /**
     * Get all pending research awaiting approval
     */
    public List<NameResearchAdminDTO> getPendingResearch() {
        return nameResearchRepository.findByStatus(NameResearch.ResearchStatus.PENDING)
            .stream()
            .map(this::toAdminDTO)
            .collect(Collectors.toList());
    }

    /**
     * Get pending research with pagination
     */
    public Page<NameResearchAdminDTO> getPendingResearch(Pageable pageable) {
        return nameResearchRepository.findByStatus(NameResearch.ResearchStatus.PENDING, pageable)
            .map(this::toAdminDTO);
    }

    /**
     * Import name research data (creates as PENDING)
     */
    @Transactional
    public NameResearchAdminDTO importResearch(NameResearchImportDTO importDTO) {
        // Check if research already exists
        Optional<NameResearch> existing = nameResearchRepository.findByNameIgnoreCase(importDTO.getName());
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Research already exists for name: " + importDTO.getName());
        }

        // Convert pronunciation to JSON
        String pronunciationJson = null;
        if (importDTO.getPronunciation() != null) {
            try {
                pronunciationJson = objectMapper.writeValueAsString(importDTO.getPronunciation());
            } catch (Exception e) {
                log.error("Failed to serialize pronunciation", e);
            }
        }

        // Convert sources to JSON
        String sourcesJson = null;
        if (importDTO.getSources() != null && !importDTO.getSources().isEmpty()) {
            try {
                sourcesJson = objectMapper.writeValueAsString(importDTO.getSources());
            } catch (Exception e) {
                log.error("Failed to serialize sources", e);
            }
        }

        // Create main research entity
        NameResearch research = NameResearch.builder()
            .name(importDTO.getName())
            .etymology(importDTO.getEtymology())
            .meaning(importDTO.getMeaning())
            .rootLanguage(importDTO.getRootLanguage())
            .history(importDTO.getHistory())
            .pronunciation(pronunciationJson)
            .sources(sourcesJson)
            .genderClassification(NameResearch.GenderClassification.valueOf(importDTO.getGenderClassification()))
            .confidenceScore(importDTO.getConfidenceScore())
            .status(NameResearch.ResearchStatus.PENDING)
            .build();

        // Add cultural usages
        if (importDTO.getCulturalUsages() != null) {
            for (NameResearchImportDTO.CulturalUsageImportDTO usage : importDTO.getCulturalUsages()) {
                NameCulturalUsage culturalUsage = NameCulturalUsage.builder()
                    .culturalOrigin(usage.getCulture())
                    .culturalMeaning(usage.getCulturalMeaning())
                    .prevalence(usage.getPrevalence())
                    .build();
                research.addCulturalUsage(culturalUsage);
            }
        }

        // Add related names
        if (importDTO.getRelatedNames() != null) {
            for (NameResearchImportDTO.RelatedNameImportDTO related : importDTO.getRelatedNames()) {
                NameRelationship relationship = NameRelationship.builder()
                    .relatedName(related.getName())
                    .relationshipType(NameRelationship.RelationshipType.valueOf(related.getType()))
                    .build();
                research.addRelatedName(relationship);
            }
        }

        // Add categories
        if (importDTO.getCategories() != null) {
            for (String category : importDTO.getCategories()) {
                NameCategory nameCategory = NameCategory.builder()
                    .category(category)
                    .build();
                research.addCategory(nameCategory);
            }
        }

        NameResearch saved = nameResearchRepository.save(research);
        log.info("Imported research for name: {} with status PENDING", saved.getName());

        return toAdminDTO(saved);
    }

    /**
     * Update existing research (preserves status)
     */
    @Transactional
    public NameResearchAdminDTO updateResearch(Long id, NameResearchImportDTO updateDTO) {
        NameResearch research = nameResearchRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Research not found: " + id));

        // Convert pronunciation to JSON
        String pronunciationJson = null;
        if (updateDTO.getPronunciation() != null) {
            try {
                pronunciationJson = objectMapper.writeValueAsString(updateDTO.getPronunciation());
            } catch (Exception e) {
                log.error("Failed to serialize pronunciation", e);
            }
        }

        // Convert sources to JSON
        String sourcesJson = null;
        if (updateDTO.getSources() != null && !updateDTO.getSources().isEmpty()) {
            try {
                sourcesJson = objectMapper.writeValueAsString(updateDTO.getSources());
            } catch (Exception e) {
                log.error("Failed to serialize sources", e);
            }
        }

        // Update basic fields (preserve status and name)
        research.setEtymology(updateDTO.getEtymology());
        research.setMeaning(updateDTO.getMeaning());
        research.setRootLanguage(updateDTO.getRootLanguage());
        research.setHistory(updateDTO.getHistory());
        research.setPronunciation(pronunciationJson);
        research.setSources(sourcesJson);
        research.setGenderClassification(NameResearch.GenderClassification.valueOf(updateDTO.getGenderClassification()));
        research.setConfidenceScore(updateDTO.getConfidenceScore());

        // Clear and replace cultural usages
        research.getCulturalUsages().clear();
        if (updateDTO.getCulturalUsages() != null) {
            for (NameResearchImportDTO.CulturalUsageImportDTO usage : updateDTO.getCulturalUsages()) {
                NameCulturalUsage culturalUsage = NameCulturalUsage.builder()
                    .culturalOrigin(usage.getCulture())
                    .culturalMeaning(usage.getCulturalMeaning())
                    .prevalence(usage.getPrevalence())
                    .build();
                research.addCulturalUsage(culturalUsage);
            }
        }

        // Clear and replace related names
        research.getRelatedNames().clear();
        if (updateDTO.getRelatedNames() != null) {
            for (NameResearchImportDTO.RelatedNameImportDTO related : updateDTO.getRelatedNames()) {
                NameRelationship relationship = NameRelationship.builder()
                    .relatedName(related.getName())
                    .relationshipType(NameRelationship.RelationshipType.valueOf(related.getType()))
                    .build();
                research.addRelatedName(relationship);
            }
        }

        // Clear and replace categories
        research.getCategories().clear();
        if (updateDTO.getCategories() != null) {
            for (String category : updateDTO.getCategories()) {
                NameCategory nameCategory = NameCategory.builder()
                    .category(category)
                    .build();
                research.addCategory(nameCategory);
            }
        }

        NameResearch saved = nameResearchRepository.save(research);
        log.info("Updated research for name: {} (status preserved: {})", saved.getName(), saved.getStatus());

        return toAdminDTO(saved);
    }

    /**
     * Approve research (makes it visible to public API)
     */
    @Transactional
    public NameResearchAdminDTO approveResearch(Long id) {
        NameResearch research = nameResearchRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Research not found: " + id));

        research.setStatus(NameResearch.ResearchStatus.APPROVED);
        NameResearch saved = nameResearchRepository.save(research);

        log.info("Approved research for name: {}", saved.getName());
        return toAdminDTO(saved);
    }

    /**
     * Reject research with notes
     */
    @Transactional
    public NameResearchAdminDTO rejectResearch(Long id, String notes) {
        NameResearch research = nameResearchRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Research not found: " + id));

        research.setStatus(NameResearch.ResearchStatus.REJECTED);
        research.setReviewNotes(notes);
        NameResearch saved = nameResearchRepository.save(research);

        log.info("Rejected research for name: {}", saved.getName());
        return toAdminDTO(saved);
    }

    /**
     * Bulk approve multiple research entries
     */
    @Transactional
    public List<NameResearchAdminDTO> bulkApprove(List<Long> ids) {
        List<NameResearch> researchList = nameResearchRepository.findAllById(ids);

        for (NameResearch research : researchList) {
            research.setStatus(NameResearch.ResearchStatus.APPROVED);
        }

        List<NameResearch> saved = nameResearchRepository.saveAll(researchList);
        log.info("Bulk approved {} research entries", saved.size());

        return saved.stream()
            .map(this::toAdminDTO)
            .collect(Collectors.toList());
    }

    /**
     * Get research coverage statistics
     */
    public ResearchStatsDTO getResearchStats() {
        long totalNames = ssaNameRepository.countDistinctNames();
        long totalResearch = nameResearchRepository.count();
        long approved = nameResearchRepository.countByStatus(NameResearch.ResearchStatus.APPROVED);
        long pending = nameResearchRepository.countByStatus(NameResearch.ResearchStatus.PENDING);
        long rejected = nameResearchRepository.countByStatus(NameResearch.ResearchStatus.REJECTED);

        double coverage = totalNames > 0 ? (approved * 100.0 / totalNames) : 0.0;

        return ResearchStatsDTO.builder()
            .totalResearch(totalResearch)
            .approvedResearch(approved)
            .pendingResearch(pending)
            .rejectedResearch(rejected)
            .totalNames(totalNames)
            .coveragePercentage(Math.round(coverage * 100.0) / 100.0)
            .build();
    }

    /**
     * Get names that need research (by SSA popularity)
     * Uses efficient SQL query to avoid loading all data into memory
     */
    public List<NameToResearchDTO> getNamesNeedingResearch(int limit) {
        // Use native SQL query to efficiently find unresearched names by popularity
        List<Object[]> results = ssaNameRepository.findNamesNeedingResearch(limit);

        return results.stream()
            .map(row -> NameToResearchDTO.builder()
                .name((String) row[0])
                .sex((String) row[1])
                .totalCount(((Number) row[2]).longValue())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Convert entity to public DTO
     */
    private NameResearchDTO toDTO(NameResearch research) {
        // Parse pronunciation JSON
        NameResearchDTO.PronunciationDTO pronunciationDTO = null;
        if (research.getPronunciation() != null) {
            try {
                pronunciationDTO = objectMapper.readValue(
                    research.getPronunciation(),
                    NameResearchDTO.PronunciationDTO.class
                );
            } catch (Exception e) {
                log.error("Failed to parse pronunciation JSON", e);
            }
        }

        // Parse sources JSON
        List<String> sourcesList = null;
        if (research.getSources() != null) {
            try {
                sourcesList = objectMapper.readValue(
                    research.getSources(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
            } catch (Exception e) {
                log.error("Failed to parse sources JSON", e);
            }
        }

        return NameResearchDTO.builder()
            .id(research.getId())
            .name(research.getName())
            .etymology(research.getEtymology())
            .meaning(research.getMeaning())
            .rootLanguage(research.getRootLanguage())
            .history(research.getHistory())
            .pronunciation(pronunciationDTO)
            .genderClassification(research.getGenderClassification().name())
            .confidenceScore(research.getConfidenceScore())
            .culturalUsages(research.getCulturalUsages().stream()
                .map(usage -> CulturalUsageDTO.builder()
                    .culturalOrigin(usage.getCulturalOrigin())
                    .culturalMeaning(usage.getCulturalMeaning())
                    .prevalence(usage.getPrevalence())
                    .build())
                .collect(Collectors.toList()))
            .relatedNames(research.getRelatedNames().stream()
                .map(rel -> RelatedNameDTO.builder()
                    .name(rel.getRelatedName())
                    .relationshipType(rel.getRelationshipType().name())
                    .build())
                .collect(Collectors.toList()))
            .categories(research.getCategories().stream()
                .map(NameCategory::getCategory)
                .collect(Collectors.toList()))
            .sources(sourcesList)
            .updatedAt(research.getUpdatedAt())
            .build();
    }

    /**
     * Convert entity to admin DTO (includes status and review notes)
     */
    private NameResearchAdminDTO toAdminDTO(NameResearch research) {
        // Parse pronunciation JSON
        NameResearchDTO.PronunciationDTO pronunciationDTO = null;
        if (research.getPronunciation() != null) {
            try {
                pronunciationDTO = objectMapper.readValue(
                    research.getPronunciation(),
                    NameResearchDTO.PronunciationDTO.class
                );
            } catch (Exception e) {
                log.error("Failed to parse pronunciation JSON", e);
            }
        }

        // Parse sources JSON
        List<String> sourcesList = null;
        if (research.getSources() != null) {
            try {
                sourcesList = objectMapper.readValue(
                    research.getSources(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
            } catch (Exception e) {
                log.error("Failed to parse sources JSON", e);
            }
        }

        return NameResearchAdminDTO.builder()
            .id(research.getId())
            .name(research.getName())
            .etymology(research.getEtymology())
            .meaning(research.getMeaning())
            .rootLanguage(research.getRootLanguage())
            .history(research.getHistory())
            .pronunciation(pronunciationDTO)
            .genderClassification(research.getGenderClassification().name())
            .confidenceScore(research.getConfidenceScore())
            .status(research.getStatus().name())
            .reviewNotes(research.getReviewNotes())
            .culturalUsages(research.getCulturalUsages().stream()
                .map(usage -> CulturalUsageDTO.builder()
                    .culturalOrigin(usage.getCulturalOrigin())
                    .culturalMeaning(usage.getCulturalMeaning())
                    .prevalence(usage.getPrevalence())
                    .build())
                .collect(Collectors.toList()))
            .relatedNames(research.getRelatedNames().stream()
                .map(rel -> RelatedNameDTO.builder()
                    .name(rel.getRelatedName())
                    .relationshipType(rel.getRelationshipType().name())
                    .build())
                .collect(Collectors.toList()))
            .categories(research.getCategories().stream()
                .map(NameCategory::getCategory)
                .collect(Collectors.toList()))
            .sources(sourcesList)
            .createdAt(research.getCreatedAt())
            .updatedAt(research.getUpdatedAt())
            .build();
    }
}

package com.flicknames.service.research.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Core name research data with etymology, meaning, and cultural context.
 * Includes approval workflow (PENDING -> APPROVED/REJECTED).
 */
@Entity
@Table(name = "name_research",
    uniqueConstraints = @UniqueConstraint(name = "uk_name_research_name", columnNames = {"name"}),
    indexes = {
        @Index(name = "idx_name_research_status", columnList = "status"),
        @Index(name = "idx_name_research_root_language", columnList = "rootLanguage"),
        @Index(name = "idx_name_research_gender", columnList = "genderClassification")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameResearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The name (normalized, e.g., "Jason")
     */
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /**
     * Etymology - origin and root meaning
     */
    @Column(columnDefinition = "TEXT")
    private String etymology;

    /**
     * Brief meaning (1-2 sentences)
     */
    @Column(length = 500)
    private String meaning;

    /**
     * Primary language of origin (Hebrew, Greek, Latin, Germanic, Celtic, Arabic, Sanskrit, etc.)
     */
    @Column(length = 50)
    private String rootLanguage;

    /**
     * Historical context, when/how adopted
     */
    @Column(columnDefinition = "TEXT")
    private String history;

    /**
     * Pronunciation data stored as JSON
     * Example: {"ipa": "/ˈdʒeɪsən/", "respelling": "JAY-sun"}
     */
    @Column(columnDefinition = "TEXT")
    private String pronunciation;

    /**
     * Gender classification
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenderClassification genderClassification;

    /**
     * Confidence score (0-100)
     */
    @Column(nullable = false)
    private Integer confidenceScore;

    /**
     * Approval status
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ResearchStatus status = ResearchStatus.PENDING;

    /**
     * Optional notes from review
     */
    @Column(columnDefinition = "TEXT")
    private String reviewNotes;

    /**
     * Cultural usages for this name
     */
    @OneToMany(mappedBy = "nameResearch", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NameCulturalUsage> culturalUsages = new ArrayList<>();

    /**
     * Related names
     */
    @OneToMany(mappedBy = "nameResearch", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NameRelationship> relatedNames = new ArrayList<>();

    /**
     * Categories/tags
     */
    @OneToMany(mappedBy = "nameResearch", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<NameCategory> categories = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Helper methods to manage relationships
     */
    public void addCulturalUsage(NameCulturalUsage usage) {
        culturalUsages.add(usage);
        usage.setNameResearch(this);
    }

    public void addRelatedName(NameRelationship relationship) {
        relatedNames.add(relationship);
        relationship.setNameResearch(this);
    }

    public void addCategory(NameCategory category) {
        categories.add(category);
        category.setNameResearch(this);
    }

    public enum GenderClassification {
        MASCULINE,
        FEMININE,
        UNISEX
    }

    public enum ResearchStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}

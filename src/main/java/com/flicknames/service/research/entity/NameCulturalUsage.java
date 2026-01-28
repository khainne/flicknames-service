package com.flicknames.service.research.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cultural origin and usage data for a name.
 * One name can have multiple cultural contexts (e.g., Biblical, English, Greek).
 */
@Entity
@Table(name = "name_cultural_usage",
    indexes = {
        @Index(name = "idx_cultural_usage_name_research", columnList = "name_research_id"),
        @Index(name = "idx_cultural_usage_culture", columnList = "culturalOrigin")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameCulturalUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "name_research_id", nullable = false)
    private NameResearch nameResearch;

    /**
     * Cultural origin (e.g., "English", "Biblical", "Greek Mythology", "Arabic")
     */
    @Column(nullable = false, length = 100)
    private String culturalOrigin;

    /**
     * Culture-specific meaning if different from main meaning
     */
    @Column(length = 500)
    private String culturalMeaning;

    /**
     * Prevalence in this culture (1-5 scale)
     * 1 = rare, 5 = very common
     */
    @Column(nullable = false)
    private Integer prevalence;
}

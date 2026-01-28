package com.flicknames.service.research.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Related names (variants, diminutives, cognates, etc.)
 */
@Entity
@Table(name = "name_relationship",
    indexes = {
        @Index(name = "idx_name_relationship_name_research", columnList = "name_research_id"),
        @Index(name = "idx_name_relationship_related_name", columnList = "relatedName"),
        @Index(name = "idx_name_relationship_type", columnList = "relationshipType")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "name_research_id", nullable = false)
    private NameResearch nameResearch;

    /**
     * The related name (e.g., "Jayson" for "Jason")
     */
    @Column(nullable = false, length = 50)
    private String relatedName;

    /**
     * Type of relationship
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RelationshipType relationshipType;

    public enum RelationshipType {
        VARIANT,          // Spelling variant (e.g., Jayson)
        DIMINUTIVE,       // Shortened form (e.g., Jay)
        FEMININE_FORM,    // Female equivalent (e.g., Jasmine)
        MASCULINE_FORM,   // Male equivalent
        COGNATE          // Related name from same root in different language
    }
}

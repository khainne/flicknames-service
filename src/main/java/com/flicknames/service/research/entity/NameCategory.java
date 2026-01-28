package com.flicknames.service.research.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Categories/tags for names (e.g., Biblical, Mythology, Royal, Modern)
 */
@Entity
@Table(name = "name_category",
    indexes = {
        @Index(name = "idx_name_category_name_research", columnList = "name_research_id"),
        @Index(name = "idx_name_category_category", columnList = "category")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NameCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "name_research_id", nullable = false)
    private NameResearch nameResearch;

    /**
     * Category name (e.g., "Biblical", "Greek Mythology", "Royal", "Modern", "Nature")
     */
    @Column(nullable = false, length = 50)
    private String category;
}

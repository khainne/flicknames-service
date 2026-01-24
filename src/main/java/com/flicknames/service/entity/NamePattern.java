package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Stores patterns used for classifying character names.
 * Patterns can be titles (Officer, Dr.), role descriptors (Guard, Woman),
 * or adjective prefixes (Old, Young) that help identify non-personal names.
 */
@Entity
@Table(name = "name_patterns", indexes = {
    @Index(name = "idx_pattern_type", columnList = "patternType"),
    @Index(name = "idx_pattern_value", columnList = "patternValue")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NamePattern {

    public enum PatternType {
        /** Titles/honorifics that precede surnames (Officer, Dr., Captain) */
        TITLE,
        /** Role descriptors indicating non-personal names (Guard, Woman, Customer) */
        ROLE_DESCRIPTOR,
        /** Adjective prefixes used with role descriptors (Old, Young, Angry) */
        ADJECTIVE_PREFIX
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatternType patternType;

    /** The pattern value, stored lowercase for matching */
    @Column(nullable = false, length = 50)
    private String patternValue;

    /** Optional note about why this pattern was added */
    @Column(length = 200)
    private String note;

    /** Example character name that triggered adding this pattern */
    @Column(length = 100)
    private String exampleName;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A given name written as two words ("Juan Carlos", "Mary Anne"). When a person's full name starts with
 * one and has at least one word after it, both words are their first name. {@code baseName} is the first
 * word, so statistics can later roll "Mary Anne" up into "Mary". Disabled rather than deleted, so the
 * startup seed does not bring a removed name back.
 */
@Entity
@Table(name = "compound_given_names", indexes = {
    @Index(name = "idx_compound_given_name_folded", columnList = "foldedName", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompoundGivenName {

    public enum Source { SEED, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** As displayed: "José Luis" */
    @Column(nullable = false, length = 60)
    private String name;

    /** Lowercase and accent-free, for matching: "jose luis" */
    @Column(nullable = false, length = 60)
    private String foldedName;

    @Column(nullable = false, length = 30)
    private String baseName;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

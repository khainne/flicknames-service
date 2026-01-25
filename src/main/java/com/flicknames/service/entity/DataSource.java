package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tracks which external IDs we've already fetched to avoid redundant API calls
 */
@Entity
@Table(name = "data_sources", indexes = {
    @Index(name = "idx_source_type_external", columnList = "sourceType,externalId", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    @Column(nullable = false)
    private String externalId; // TMDB ID, IMDb ID, etc.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntityType entityType;

    private Long internalId; // Our database ID for the entity

    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    private LocalDateTime lastUpdatedAt;

    @Enumerated(EnumType.STRING)
    private FetchStatus status;

    private String errorMessage;

    public enum SourceType {
        TMDB,
        IMDB,
        SSA
    }

    public enum EntityType {
        MOVIE,
        PERSON,
        CHARACTER,
        NAME
    }

    public enum FetchStatus {
        SUCCESS,
        FAILED,
        PARTIAL
    }
}

package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Tracks SSA data imports to prevent duplicate imports
 * and detect when new data is available.
 */
@Entity
@Table(name = "ssa_import_metadata",
    indexes = {
        @Index(name = "idx_ssa_import_type_year", columnList = "datasetType, dataYear")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsaImportMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Type of dataset imported
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DatasetType datasetType;

    /**
     * The most recent year contained in the imported dataset.
     * Null if import is still in progress or failed before completion.
     */
    @Column(nullable = true)
    private Integer dataYear;

    /**
     * URL the data was downloaded from
     */
    @Column(length = 500)
    private String sourceUrl;

    /**
     * MD5 or SHA-256 checksum of the downloaded file.
     * Used to detect if the file has been updated.
     */
    @Column(length = 64)
    private String fileChecksum;

    /**
     * Total number of records imported
     */
    private Long recordCount;

    /**
     * Number of unique names imported
     */
    private Long nameCount;

    /**
     * Import status
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status;

    /**
     * Error message if import failed
     */
    @Column(length = 1000)
    private String errorMessage;

    /**
     * Duration of import in milliseconds
     */
    private Long importDurationMs;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime importedAt;

    public enum DatasetType {
        NATIONAL,
        STATE
    }

    public enum ImportStatus {
        IN_PROGRESS,
        SUCCESS,
        FAILED,
        PARTIAL
    }
}

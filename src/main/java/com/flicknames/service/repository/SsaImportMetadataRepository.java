package com.flicknames.service.repository;

import com.flicknames.service.entity.SsaImportMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SsaImportMetadataRepository extends JpaRepository<SsaImportMetadata, Long> {

    // Find latest successful import for a dataset type
    @Query("""
        SELECT m FROM SsaImportMetadata m
        WHERE m.datasetType = :datasetType
        AND m.status = 'SUCCESS'
        ORDER BY m.importedAt DESC
        """)
    List<SsaImportMetadata> findLatestSuccessfulImport(
        @Param("datasetType") SsaImportMetadata.DatasetType datasetType
    );

    // Find by checksum to detect if file has changed
    Optional<SsaImportMetadata> findByFileChecksumAndDatasetType(
        String fileChecksum,
        SsaImportMetadata.DatasetType datasetType
    );

    // Check if a specific year has been imported
    @Query("""
        SELECT COUNT(m) > 0 FROM SsaImportMetadata m
        WHERE m.datasetType = :datasetType
        AND m.dataYear = :dataYear
        AND m.status = 'SUCCESS'
        """)
    boolean existsByDatasetTypeAndDataYear(
        @Param("datasetType") SsaImportMetadata.DatasetType datasetType,
        @Param("dataYear") Integer dataYear
    );

    // All imports ordered by date
    List<SsaImportMetadata> findAllByOrderByImportedAtDesc();

    // Recent imports by type
    @Query("""
        SELECT m FROM SsaImportMetadata m
        WHERE m.datasetType = :datasetType
        ORDER BY m.importedAt DESC
        """)
    List<SsaImportMetadata> findRecentByDatasetType(
        @Param("datasetType") SsaImportMetadata.DatasetType datasetType
    );
}

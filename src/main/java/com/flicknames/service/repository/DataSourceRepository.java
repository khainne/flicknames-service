package com.flicknames.service.repository;

import com.flicknames.service.entity.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    Optional<DataSource> findBySourceTypeAndExternalIdAndEntityType(
            DataSource.SourceType sourceType,
            String externalId,
            DataSource.EntityType entityType
    );

    boolean existsBySourceTypeAndExternalIdAndEntityTypeAndStatus(
            DataSource.SourceType sourceType,
            String externalId,
            DataSource.EntityType entityType,
            DataSource.FetchStatus status
    );
}

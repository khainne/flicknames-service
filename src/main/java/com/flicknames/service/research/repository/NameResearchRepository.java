package com.flicknames.service.research.repository;

import com.flicknames.service.research.entity.NameResearch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NameResearchRepository extends JpaRepository<NameResearch, Long> {

    /**
     * Find research by name (case-insensitive)
     */
    Optional<NameResearch> findByNameIgnoreCase(String name);

    /**
     * Find all research by status
     */
    Page<NameResearch> findByStatus(NameResearch.ResearchStatus status, Pageable pageable);

    /**
     * Find all pending research
     */
    List<NameResearch> findByStatus(NameResearch.ResearchStatus status);

    /**
     * Count by status
     */
    long countByStatus(NameResearch.ResearchStatus status);

    /**
     * Find by root language
     */
    List<NameResearch> findByRootLanguage(String rootLanguage);

    /**
     * Find by gender classification
     */
    List<NameResearch> findByGenderClassification(NameResearch.GenderClassification gender);

    /**
     * Check if research exists for a name
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Get research statistics
     */
    @Query("SELECT COUNT(nr) FROM NameResearch nr WHERE nr.status = :status")
    long countByStatusQuery(@Param("status") NameResearch.ResearchStatus status);
}

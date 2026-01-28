package com.flicknames.service.research.repository;

import com.flicknames.service.research.entity.NameCulturalUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NameCulturalUsageRepository extends JpaRepository<NameCulturalUsage, Long> {

    /**
     * Find all cultural usages by name research ID
     */
    List<NameCulturalUsage> findByNameResearchId(Long nameResearchId);

    /**
     * Find all by cultural origin
     */
    List<NameCulturalUsage> findByCulturalOrigin(String culturalOrigin);
}

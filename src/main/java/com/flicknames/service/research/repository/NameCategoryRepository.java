package com.flicknames.service.research.repository;

import com.flicknames.service.research.entity.NameCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NameCategoryRepository extends JpaRepository<NameCategory, Long> {

    /**
     * Find all categories by name research ID
     */
    List<NameCategory> findByNameResearchId(Long nameResearchId);

    /**
     * Find all by category name
     */
    List<NameCategory> findByCategory(String category);
}

package com.flicknames.service.research.repository;

import com.flicknames.service.research.entity.NameRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NameRelationshipRepository extends JpaRepository<NameRelationship, Long> {

    /**
     * Find all relationships by name research ID
     */
    List<NameRelationship> findByNameResearchId(Long nameResearchId);

    /**
     * Find all by relationship type
     */
    List<NameRelationship> findByRelationshipType(NameRelationship.RelationshipType type);

    /**
     * Find relationships for a related name
     */
    List<NameRelationship> findByRelatedNameIgnoreCase(String relatedName);
}

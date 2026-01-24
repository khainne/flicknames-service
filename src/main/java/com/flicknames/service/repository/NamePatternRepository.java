package com.flicknames.service.repository;

import com.flicknames.service.entity.NamePattern;
import com.flicknames.service.entity.NamePattern.PatternType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NamePatternRepository extends JpaRepository<NamePattern, Long> {

    List<NamePattern> findByPatternType(PatternType patternType);

    Optional<NamePattern> findByPatternTypeAndPatternValue(PatternType patternType, String patternValue);

    boolean existsByPatternTypeAndPatternValue(PatternType patternType, String patternValue);

    @Query("SELECT p.patternValue FROM NamePattern p WHERE p.patternType = :patternType")
    List<String> findPatternValuesByType(PatternType patternType);

    void deleteByPatternTypeAndPatternValue(PatternType patternType, String patternValue);
}

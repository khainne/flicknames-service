package com.flicknames.service.repository;

import com.flicknames.service.entity.CompoundGivenName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompoundGivenNameRepository extends JpaRepository<CompoundGivenName, Long> {

    List<CompoundGivenName> findByEnabledTrue();

    Optional<CompoundGivenName> findByFoldedName(String foldedName);
}

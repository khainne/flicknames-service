package com.flicknames.service.repository;

import com.flicknames.service.entity.CharacterNameLabel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterNameLabelRepository extends JpaRepository<CharacterNameLabel, Long> {

    Optional<CharacterNameLabel> findByFullName(String fullName);

    List<CharacterNameLabel> findByFullNameIn(Collection<String> fullNames);

    Page<CharacterNameLabel> findByVerdict(CharacterNameLabel.Verdict verdict, Pageable pageable);

    List<CharacterNameLabel> findByVerdictInOrderByIdAsc(Collection<CharacterNameLabel.Verdict> verdicts);

    /** Rows of [verdict, count] over all labels. */
    @Query("SELECT l.verdict, COUNT(l) FROM CharacterNameLabel l GROUP BY l.verdict")
    List<Object[]> countByVerdict();

    /** Rows of [verdict, count] for labels last written at or after {@code since}. */
    @Query("""
        SELECT l.verdict, COUNT(l)
        FROM CharacterNameLabel l
        WHERE l.updatedAt >= :since
        GROUP BY l.verdict
        """)
    List<Object[]> countByVerdictSince(@Param("since") LocalDateTime since);

    long countByVerdictNotAndAppliedAtIsNull(CharacterNameLabel.Verdict verdict);
}

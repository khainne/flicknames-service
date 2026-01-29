package com.flicknames.service.repository;

import com.flicknames.service.entity.SsaName;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SsaNameRepository extends JpaRepository<SsaName, Long> {

    Optional<SsaName> findByNameAndSex(String name, String sex);

    List<SsaName> findByName(String name);

    List<SsaName> findByNameIgnoreCase(String name);

    Page<SsaName> findByNameStartingWithIgnoreCaseAndSex(String prefix, String sex, Pageable pageable);

    Page<SsaName> findByNameStartingWithIgnoreCase(String prefix, Pageable pageable);

    @Query("""
        SELECT n FROM SsaName n
        LEFT JOIN FETCH n.yearlyStats ys
        WHERE n.name = :name AND n.sex = :sex
        ORDER BY ys.year DESC
        """)
    Optional<SsaName> findByNameAndSexWithHistory(
        @Param("name") String name,
        @Param("sex") String sex
    );

    @Query("""
        SELECT DISTINCT n.name FROM SsaName n
        ORDER BY n.name
        """)
    List<String> findAllDistinctNames();

    @Query("""
        SELECT COUNT(DISTINCT n.name) FROM SsaName n
        """)
    long countDistinctNames();

    @Query("""
        SELECT n.sex, COUNT(n) FROM SsaName n
        GROUP BY n.sex
        """)
    List<Object[]> countBySex();

    /**
     * Find names needing research (not in name_research table) ordered by popularity
     * Returns: name, sex, total_count
     */
    @Query(value = """
        SELECT
            n.name,
            n.sex,
            COALESCE(SUM(ys.count), 0) as total_count
        FROM ssa_name n
        LEFT JOIN ssa_yearly_stat ys ON ys.ssa_name_id = n.id
        WHERE LOWER(n.name) NOT IN (
            SELECT LOWER(nr.name) FROM name_research nr
        )
        GROUP BY n.name, n.sex
        ORDER BY total_count DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findNamesNeedingResearch(@Param("limit") int limit);
}

package com.flicknames.service.repository;

import com.flicknames.service.entity.SsaNameStateBreakdown;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SsaNameStateBreakdownRepository extends JpaRepository<SsaNameStateBreakdown, Long> {

    // State breakdown for a specific name+year
    @Query("""
        SELECT sb FROM SsaNameStateBreakdown sb
        JOIN sb.yearlyStat ys
        JOIN ys.ssaName n
        WHERE n.name = :name AND n.sex = :sex AND ys.year = :year
        ORDER BY sb.count DESC
        """)
    List<SsaNameStateBreakdown> findByNameAndSexAndYear(
        @Param("name") String name,
        @Param("sex") String sex,
        @Param("year") Integer year
    );

    // Top names in a specific state for a year
    @Query("""
        SELECT sb FROM SsaNameStateBreakdown sb
        JOIN FETCH sb.yearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE sb.stateCode = :stateCode AND ys.year = :year AND n.sex = :sex
        ORDER BY sb.rank ASC NULLS LAST, sb.count DESC
        """)
    Page<SsaNameStateBreakdown> findTopNamesByStateAndYearAndSex(
        @Param("stateCode") String stateCode,
        @Param("year") Integer year,
        @Param("sex") String sex,
        Pageable pageable
    );

    // Name history in a specific state
    @Query("""
        SELECT sb FROM SsaNameStateBreakdown sb
        JOIN FETCH sb.yearlyStat ys
        JOIN ys.ssaName n
        WHERE n.name = :name AND n.sex = :sex AND sb.stateCode = :stateCode
        ORDER BY ys.year DESC
        """)
    List<SsaNameStateBreakdown> findNameHistoryByState(
        @Param("name") String name,
        @Param("sex") String sex,
        @Param("stateCode") String stateCode
    );

    // All state breakdowns for ranking calculation
    @Query("""
        SELECT sb FROM SsaNameStateBreakdown sb
        JOIN sb.yearlyStat ys
        JOIN ys.ssaName n
        WHERE sb.stateCode = :stateCode AND ys.year = :year AND n.sex = :sex
        ORDER BY sb.count DESC
        """)
    List<SsaNameStateBreakdown> findAllByStateAndYearAndSexOrderByCountDesc(
        @Param("stateCode") String stateCode,
        @Param("year") Integer year,
        @Param("sex") String sex
    );

    // Distinct states in the database
    @Query("SELECT DISTINCT sb.stateCode FROM SsaNameStateBreakdown sb ORDER BY sb.stateCode")
    List<String> findDistinctStateCodes();

    // Batch update for rankings
    @Modifying
    @Query("""
        UPDATE SsaNameStateBreakdown sb
        SET sb.rank = :rank
        WHERE sb.id = :id
        """)
    void updateRank(@Param("id") Long id, @Param("rank") Integer rank);

    // Check if state data exists for a year
    @Query("""
        SELECT COUNT(sb) > 0 FROM SsaNameStateBreakdown sb
        JOIN sb.yearlyStat ys
        WHERE ys.year = :year AND sb.stateCode = :stateCode
        """)
    boolean existsByYearAndState(@Param("year") Integer year, @Param("stateCode") String stateCode);

    // Count records per state
    @Query("""
        SELECT sb.stateCode, COUNT(sb) FROM SsaNameStateBreakdown sb
        GROUP BY sb.stateCode
        ORDER BY sb.stateCode
        """)
    List<Object[]> countByState();

    // Check if a state breakdown exists for a specific yearly stat + state
    boolean existsByYearlyStatIdAndStateCode(Long yearlyStatId, String stateCode);

    // Load all existing state breakdown keys for a year range (for duplicate checking during import)
    @Query("""
        SELECT CONCAT(sb.yearlyStat.id, '|', sb.stateCode)
        FROM SsaNameStateBreakdown sb
        WHERE sb.yearlyStat.id IN (
            SELECT ys.id FROM SsaNameYearlyStat ys
            WHERE ys.year BETWEEN :minYear AND :maxYear
        )
        """)
    List<String> findExistingBreakdownKeysByYearRange(@Param("minYear") Integer minYear, @Param("maxYear") Integer maxYear);
}

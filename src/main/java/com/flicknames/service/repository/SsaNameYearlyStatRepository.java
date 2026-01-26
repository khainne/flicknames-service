package com.flicknames.service.repository;

import com.flicknames.service.entity.SsaNameYearlyStat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SsaNameYearlyStatRepository extends JpaRepository<SsaNameYearlyStat, Long> {

    // Top names for a year
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE ys.year = :year AND n.sex = :sex
        ORDER BY ys.rank ASC NULLS LAST, ys.count DESC
        """)
    Page<SsaNameYearlyStat> findTopNamesByYearAndSex(
        @Param("year") Integer year,
        @Param("sex") String sex,
        Pageable pageable
    );

    // Name history across all years
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE n.name = :name AND n.sex = :sex
        ORDER BY ys.year DESC
        """)
    List<SsaNameYearlyStat> findNameHistory(
        @Param("name") String name,
        @Param("sex") String sex
    );

    // Peak year for a name (highest rank)
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN ys.ssaName n
        WHERE n.name = :name AND n.sex = :sex
        AND ys.rank IS NOT NULL
        ORDER BY ys.rank ASC
        """)
    List<SsaNameYearlyStat> findPeakYears(
        @Param("name") String name,
        @Param("sex") String sex,
        Pageable pageable
    );

    // Rising names (biggest rank improvement year-over-year)
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE ys.year = :year AND n.sex = :sex
        AND ys.rankChange IS NOT NULL
        AND ys.rankChange > 0
        ORDER BY ys.rankChange DESC
        """)
    Page<SsaNameYearlyStat> findRisingNames(
        @Param("year") Integer year,
        @Param("sex") String sex,
        Pageable pageable
    );

    // Falling names (biggest rank decline year-over-year)
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE ys.year = :year AND n.sex = :sex
        AND ys.rankChange IS NOT NULL
        AND ys.rankChange < 0
        ORDER BY ys.rankChange ASC
        """)
    Page<SsaNameYearlyStat> findFallingNames(
        @Param("year") Integer year,
        @Param("sex") String sex,
        Pageable pageable
    );

    // Stats for a specific name and year
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE n.name = :name AND n.sex = :sex AND ys.year = :year
        """)
    Optional<SsaNameYearlyStat> findByNameAndSexAndYear(
        @Param("name") String name,
        @Param("sex") String sex,
        @Param("year") Integer year
    );

    // Get all stats for a year (for ranking calculation)
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN ys.ssaName n
        WHERE ys.year = :year AND n.sex = :sex
        ORDER BY ys.count DESC
        """)
    List<SsaNameYearlyStat> findAllByYearAndSexOrderByCountDesc(
        @Param("year") Integer year,
        @Param("sex") String sex
    );

    // Total births for a year+sex (for proportion calculation)
    @Query("""
        SELECT SUM(ys.count) FROM SsaNameYearlyStat ys
        JOIN ys.ssaName n
        WHERE ys.year = :year AND n.sex = :sex
        """)
    Long sumCountByYearAndSex(
        @Param("year") Integer year,
        @Param("sex") String sex
    );

    // Years available in the database
    @Query("SELECT DISTINCT ys.year FROM SsaNameYearlyStat ys ORDER BY ys.year DESC")
    List<Integer> findDistinctYears();

    // Min and max years
    @Query("SELECT MIN(ys.year) FROM SsaNameYearlyStat ys")
    Integer findMinYear();

    @Query("SELECT MAX(ys.year) FROM SsaNameYearlyStat ys")
    Integer findMaxYear();

    // Batch update for rankings
    @Modifying
    @Query("""
        UPDATE SsaNameYearlyStat ys
        SET ys.rank = :rank
        WHERE ys.id = :id
        """)
    void updateRank(@Param("id") Long id, @Param("rank") Integer rank);

    // Check if year data exists
    boolean existsByYear(Integer year);

    // Count records per year
    @Query("""
        SELECT ys.year, COUNT(ys) FROM SsaNameYearlyStat ys
        GROUP BY ys.year
        ORDER BY ys.year DESC
        """)
    List<Object[]> countByYear();

    // Find all stats for a year range (for caching during state import)
    @Query("""
        SELECT ys FROM SsaNameYearlyStat ys
        JOIN FETCH ys.ssaName n
        WHERE ys.year BETWEEN :minYear AND :maxYear
        """)
    List<SsaNameYearlyStat> findAllByYearRange(
        @Param("minYear") Integer minYear,
        @Param("maxYear") Integer maxYear
    );
}

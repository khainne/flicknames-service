package com.flicknames.service.repository;

import com.flicknames.service.entity.ScreenCharacter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScreenCharacterRepository extends JpaRepository<ScreenCharacter, Long> {

    Optional<ScreenCharacter> findByFullName(String fullName);

    // Name type queries for migration
    Page<ScreenCharacter> findByNameType(ScreenCharacter.NameType nameType, Pageable pageable);
    long countByNameType(ScreenCharacter.NameType nameType);
    long countByNameTypeNot(ScreenCharacter.NameType nameType);

    // First Name Aggregation Queries for Characters
    // Only includes characters with valid first names (firstName is not null after parsing)
    @Query("""
        SELECT c.firstName, SUM(m.revenue) as totalRevenue, COUNT(DISTINCT m.id) as movieCount, COUNT(DISTINCT c.id) as characterCount
        FROM ScreenCharacter c
        JOIN c.credits cr
        JOIN cr.movie m
        WHERE m.releaseDate BETWEEN :startDate AND :endDate
        AND m.revenue IS NOT NULL
        AND c.firstName IS NOT NULL
        GROUP BY c.firstName
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTrendingNamesByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    @Query("""
        SELECT c.firstName, SUM(m.revenue) as totalRevenue, COUNT(DISTINCT m.id) as movieCount, COUNT(DISTINCT c.id) as characterCount
        FROM ScreenCharacter c
        JOIN c.credits cr
        JOIN cr.movie m
        WHERE YEAR(m.releaseDate) = :year
        AND m.revenue IS NOT NULL
        AND c.firstName IS NOT NULL
        GROUP BY c.firstName
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTopNamesByYear(
        @Param("year") int year,
        Pageable pageable
    );

    @Query("""
        SELECT c
        FROM ScreenCharacter c
        WHERE c.firstName = :firstName
        ORDER BY c.lastName ASC, c.fullName ASC
        """)
    List<ScreenCharacter> findByFirstName(@Param("firstName") String firstName);

    @Query("""
        SELECT DISTINCT c.firstName
        FROM ScreenCharacter c
        ORDER BY c.firstName ASC
        """)
    List<String> findAllDistinctFirstNames();

    @Query("""
        SELECT c
        FROM ScreenCharacter c
        JOIN c.credits cr
        WHERE cr.movie.id = :movieId
        ORDER BY cr.order ASC
        """)
    List<ScreenCharacter> findByMovieId(@Param("movieId") Long movieId);
}

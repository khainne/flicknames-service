package com.flicknames.service.repository;

import com.flicknames.service.entity.Character;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {

    Optional<Character> findByFullName(String fullName);

    // First Name Aggregation Queries for Characters
    @Query("""
        SELECT c.firstName, SUM(m.revenue) as totalRevenue, COUNT(DISTINCT m.id) as movieCount, COUNT(DISTINCT c.id) as characterCount
        FROM Character c
        JOIN c.credits cr
        JOIN cr.movie m
        WHERE m.releaseDate BETWEEN :startDate AND :endDate
        AND m.revenue IS NOT NULL
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
        FROM Character c
        JOIN c.credits cr
        JOIN cr.movie m
        WHERE YEAR(m.releaseDate) = :year
        AND m.revenue IS NOT NULL
        GROUP BY c.firstName
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTopNamesByYear(
        @Param("year") int year,
        Pageable pageable
    );

    @Query("""
        SELECT c
        FROM Character c
        WHERE c.firstName = :firstName
        ORDER BY c.lastName ASC, c.fullName ASC
        """)
    List<Character> findByFirstName(@Param("firstName") String firstName);

    @Query("""
        SELECT DISTINCT c.firstName
        FROM Character c
        ORDER BY c.firstName ASC
        """)
    List<String> findAllDistinctFirstNames();

    @Query("""
        SELECT c
        FROM Character c
        JOIN c.credits cr
        WHERE cr.movie.id = :movieId
        ORDER BY cr.order ASC
        """)
    List<Character> findByMovieId(@Param("movieId") Long movieId);
}

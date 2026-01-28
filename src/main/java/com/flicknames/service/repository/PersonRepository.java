package com.flicknames.service.repository;

import com.flicknames.service.entity.Person;
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
public interface PersonRepository extends JpaRepository<Person, Long> {

    Optional<Person> findByTmdbPersonId(Long tmdbPersonId);

    Page<Person> findByFullNameContainingIgnoreCase(String name, Pageable pageable);

    @Query("""
        SELECT p, SUM(m.revenue) as totalRevenue
        FROM Person p
        JOIN p.credits c
        JOIN c.movie m
        WHERE m.releaseDate BETWEEN :startDate AND :endDate
        AND m.revenue IS NOT NULL
        GROUP BY p.id
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTrendingPeopleByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    @Query("""
        SELECT p, SUM(m.revenue) as totalRevenue, COUNT(DISTINCT m.id) as movieCount
        FROM Person p
        JOIN p.credits c
        JOIN c.movie m
        WHERE YEAR(m.releaseDate) = :year
        AND m.revenue IS NOT NULL
        GROUP BY p.id
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTopPeopleByYear(
        @Param("year") int year,
        Pageable pageable
    );

    @Query("""
        SELECT p
        FROM Person p
        JOIN p.credits c
        WHERE c.movie.id = :movieId
        ORDER BY c.order ASC
        """)
    List<Person> findByMovieId(@Param("movieId") Long movieId);

    // First Name Aggregation Queries
    @Query("""
        SELECT p.firstName, SUM(m.revenue) as totalRevenue, COUNT(DISTINCT m.id) as movieCount, COUNT(DISTINCT p.id) as peopleCount
        FROM Person p
        JOIN p.credits c
        JOIN c.movie m
        WHERE m.releaseDate BETWEEN :startDate AND :endDate
        AND m.revenue IS NOT NULL
        GROUP BY p.firstName
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTrendingNamesByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    @Query("""
        SELECT p.firstName, SUM(m.revenue) as totalRevenue, COUNT(DISTINCT m.id) as movieCount, COUNT(DISTINCT p.id) as peopleCount
        FROM Person p
        JOIN p.credits c
        JOIN c.movie m
        WHERE YEAR(m.releaseDate) = :year
        AND m.revenue IS NOT NULL
        GROUP BY p.firstName
        ORDER BY totalRevenue DESC
        """)
    List<Object[]> findTopNamesByYear(
        @Param("year") int year,
        Pageable pageable
    );

    @Query("""
        SELECT p
        FROM Person p
        WHERE p.firstName = :firstName
        ORDER BY p.lastName ASC
        """)
    List<Person> findByFirstName(@Param("firstName") String firstName);

    @Query("""
        SELECT DISTINCT p.firstName
        FROM Person p
        ORDER BY p.firstName ASC
        """)
    List<String> findAllDistinctFirstNames();

    @Query("""
        SELECT p
        FROM Person p
        LEFT JOIN p.credits c
        LEFT JOIN c.movie m
        WHERE p.firstName = :firstName
        GROUP BY p.id
        ORDER BY COALESCE(SUM(m.revenue), 0) DESC
        """)
    List<Person> findTopPeopleByFirstName(@Param("firstName") String firstName, Pageable pageable);
}

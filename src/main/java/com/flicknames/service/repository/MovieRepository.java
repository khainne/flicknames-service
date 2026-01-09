package com.flicknames.service.repository;

import com.flicknames.service.entity.Movie;
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
public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findByTmdbMovieId(Long tmdbMovieId);

    Page<Movie> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    @Query("""
        SELECT m
        FROM Movie m
        WHERE m.releaseDate BETWEEN :startDate AND :endDate
        ORDER BY m.revenue DESC
        """)
    List<Movie> findMoviesInTheaters(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    @Query("""
        SELECT m
        FROM Movie m
        WHERE YEAR(m.releaseDate) = :year
        AND m.revenue IS NOT NULL
        ORDER BY m.revenue DESC
        """)
    List<Movie> findTopMoviesByYear(
        @Param("year") int year,
        Pageable pageable
    );

    @Query("""
        SELECT m
        FROM Movie m
        WHERE m.releaseDate <= :currentDate
        AND m.releaseDate >= :threeMonthsAgo
        ORDER BY m.releaseDate DESC
        """)
    List<Movie> findRecentReleases(
        @Param("currentDate") LocalDate currentDate,
        @Param("threeMonthsAgo") LocalDate threeMonthsAgo,
        Pageable pageable
    );

    List<Movie> findByReleaseDateBetweenOrderByRevenueDesc(
        LocalDate startDate,
        LocalDate endDate,
        Pageable pageable
    );
}

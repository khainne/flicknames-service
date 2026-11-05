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

    /** Image backfill: TMDB movies in id order, resumable from the last id seen. */
    List<Movie> findByTmdbMovieIdIsNotNullAndIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);

    /** Revenue refresh: TMDB films released in a date range. */
    List<Movie> findByTmdbMovieIdIsNotNullAndReleaseDateBetween(java.time.LocalDate from, java.time.LocalDate to);

    /**
     * Title search, best match first: an exact title (or "The " + query), then the
     * biggest box office, then the newest release. {@code pattern} is a LIKE pattern
     * with wildcards escaped by '!'; {@code exact} is the lower-cased query.
     */
    @Query(value = """
        SELECT m
        FROM Movie m
        WHERE LOWER(m.title) LIKE :pattern ESCAPE '!'
        ORDER BY
            CASE WHEN LOWER(m.title) = :exact OR LOWER(m.title) = CONCAT('the ', :exact) THEN 0 ELSE 1 END,
            m.revenue DESC NULLS LAST,
            m.releaseDate DESC NULLS LAST,
            m.id
        """,
        countQuery = "SELECT COUNT(m) FROM Movie m WHERE LOWER(m.title) LIKE :pattern ESCAPE '!'")
    Page<Movie> searchByTitle(@Param("pattern") String pattern, @Param("exact") String exact, Pageable pageable);

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

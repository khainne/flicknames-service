package com.flicknames.service.repository;

import com.flicknames.service.entity.Credit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditRepository extends JpaRepository<Credit, Long> {

    List<Credit> findByPersonId(Long personId);

    List<Credit> findByMovieId(Long movieId);

    @Query("""
        SELECT c
        FROM Credit c
        WHERE c.person.id = :personId
        ORDER BY c.movie.releaseDate DESC
        """)
    List<Credit> findByPersonIdOrderByMovieReleaseDate(@Param("personId") Long personId);

    @Query("""
        SELECT c
        FROM Credit c
        WHERE c.movie.id = :movieId
        AND c.roleType = 'CAST'
        ORDER BY c.order ASC
        """)
    List<Credit> findCastByMovieId(@Param("movieId") Long movieId);

    @Query("""
        SELECT c
        FROM Credit c
        WHERE c.movie.id = :movieId
        AND c.roleType = 'CREW'
        ORDER BY c.department, c.job
        """)
    List<Credit> findCrewByMovieId(@Param("movieId") Long movieId);

    @Query("""
        SELECT c.job, COUNT(c)
        FROM Credit c
        WHERE c.person.id = :personId
        GROUP BY c.job
        ORDER BY COUNT(c) DESC
        """)
    List<Object[]> findJobStatsByPersonId(@Param("personId") Long personId);
}

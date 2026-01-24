package com.flicknames.service.repository;

import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditRepository extends JpaRepository<Credit, Long> {

    boolean existsByMovieAndPersonAndRoleTypeAndJob(Movie movie, Person person, Credit.RoleType roleType, String job);

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

    // Profession queries for name discovery
    @Query("""
        SELECT c.job, c.department, COUNT(DISTINCT c.person.id)
        FROM Credit c
        WHERE c.job IS NOT NULL
        GROUP BY c.job, c.department
        ORDER BY COUNT(DISTINCT c.person.id) DESC
        """)
    List<Object[]> findAllProfessionsWithCounts();

    @Query("""
        SELECT c.job, COUNT(DISTINCT c.person.id)
        FROM Credit c
        WHERE c.person.firstName = :firstName
        AND c.job IS NOT NULL
        GROUP BY c.job
        ORDER BY COUNT(DISTINCT c.person.id) DESC
        """)
    List<Object[]> findProfessionCountsByFirstName(@Param("firstName") String firstName);

    @Query("""
        SELECT DISTINCT c.person
        FROM Credit c
        WHERE c.person.firstName = :firstName
        AND (:profession IS NULL OR c.job = :profession)
        """)
    List<Person> findPeopleByFirstNameAndProfession(
        @Param("firstName") String firstName,
        @Param("profession") String profession
    );

    @Query("""
        SELECT COUNT(DISTINCT c.person.firstName)
        FROM Credit c
        WHERE c.job = :profession
        """)
    Long countUniqueNamesByProfession(@Param("profession") String profession);
}

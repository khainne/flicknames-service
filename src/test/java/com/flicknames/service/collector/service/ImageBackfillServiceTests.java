package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBCreditsDTO;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uses its own in-memory database so the rows it adds don't leak into other test classes. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:image_backfill_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImageBackfillServiceTests {

    private static final long MOVIE_TMDB_ID = 777_001L;

    @Autowired
    private ImageBackfillService backfill;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private TMDBClient tmdbClient;

    @Test
    void fillsOnlyEmptyImagePathsAndIsResumable() {
        jdbc.update("INSERT INTO movies (title, tmdb_movie_id, backdrop_path, created_at, updated_at) "
            + "VALUES ('Backfill Movie', ?, '/keep-backdrop.jpg', NOW(), NOW())", MOVIE_TMDB_ID);
        person("Ana", "Photo", 9_001L, null);
        person("Ben", "Photo", 9_002L, "/existing.jpg");
        person("Cy", "Nophoto", 9_003L, null);
        for (long tmdbPersonId : List.of(9_001L, 9_002L, 9_003L)) {
            jdbc.update("INSERT INTO credits (movie_id, person_id, role_type, department, job, created_at) "
                + "SELECT m.id, p.id, 'CAST', 'Acting', 'Actor', NOW() FROM movies m, people p "
                + "WHERE m.tmdb_movie_id = ? AND p.tmdb_person_id = ?", MOVIE_TMDB_ID, tmdbPersonId);
        }

        TMDBMovieDTO movieDto = new TMDBMovieDTO();
        movieDto.setPosterPath("/poster.jpg");
        movieDto.setBackdropPath("/new-backdrop.jpg");
        when(tmdbClient.getMovie(anyLong())).thenReturn(movieDto);
        when(tmdbClient.getMovieCredits(anyLong())).thenReturn(credits(
            cast(9_001L, "/ana.jpg"), cast(9_002L, "/replacement.jpg"), cast(9_003L, null)));

        long afterId = movieId() - 1;

        Map<String, Object> dryRun = backfill.backfill(true, afterId, 10);
        assertThat(dryRun.get("postersFilled")).isEqualTo(1);
        assertThat(dryRun.get("backdropsFilled")).isEqualTo(0);
        assertThat(dryRun.get("peopleFilled")).isEqualTo(1);
        assertThat(dryRun.get("peopleWithoutTmdbPhoto")).isEqualTo(1);
        assertThat(poster()).isNull();
        assertThat(photo(9_001L)).isNull();

        Map<String, Object> applied = backfill.backfill(false, afterId, 10);
        assertThat(applied.get("done")).isEqualTo(true);
        assertThat(applied.get("nextAfterId")).isEqualTo(movieId());
        assertThat(poster()).isEqualTo("/poster.jpg");
        assertThat(jdbc.queryForObject("SELECT backdrop_path FROM movies WHERE tmdb_movie_id = ?", String.class,
            MOVIE_TMDB_ID)).isEqualTo("/keep-backdrop.jpg");
        assertThat(photo(9_001L)).isEqualTo("/ana.jpg");
        assertThat(photo(9_002L)).isEqualTo("/existing.jpg");
        assertThat(photo(9_003L)).isNull();

        // Re-running skips the movie lookup now that poster and backdrop are both set.
        clearInvocations(tmdbClient);
        backfill.backfill(false, afterId, 10);
        verify(tmdbClient, never()).getMovie(anyLong());
    }

    @Test
    void batchSizeIsCapped() {
        Map<String, Object> report = backfill.backfill(true, Long.MAX_VALUE - 1, 50_000);
        assertThat(report.get("moviesScanned")).isEqualTo(0);
        assertThat(report.get("done")).isEqualTo(true);
    }

    private void person(String first, String last, long tmdbId, String profilePath) {
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, tmdb_person_id, profile_path, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW())", first, last, first + " " + last, tmdbId, profilePath);
    }

    private long movieId() {
        return jdbc.queryForObject("SELECT id FROM movies WHERE tmdb_movie_id = ?", Long.class, MOVIE_TMDB_ID);
    }

    private String poster() {
        return jdbc.queryForObject("SELECT poster_path FROM movies WHERE tmdb_movie_id = ?", String.class, MOVIE_TMDB_ID);
    }

    private String photo(long tmdbPersonId) {
        return jdbc.queryForObject("SELECT profile_path FROM people WHERE tmdb_person_id = ?", String.class, tmdbPersonId);
    }

    private static TMDBCreditsDTO.CastMember cast(long id, String profilePath) {
        TMDBCreditsDTO.CastMember member = new TMDBCreditsDTO.CastMember();
        member.setId(id);
        member.setName("Person " + id);
        member.setProfilePath(profilePath);
        return member;
    }

    private static TMDBCreditsDTO credits(TMDBCreditsDTO.CastMember... cast) {
        TMDBCreditsDTO dto = new TMDBCreditsDTO();
        dto.setCast(List.of(cast));
        return dto;
    }
}

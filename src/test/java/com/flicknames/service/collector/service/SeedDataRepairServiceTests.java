package com.flicknames.service.collector.service;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBPersonDTO;
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
import static org.mockito.Mockito.when;

/**
 * Runs against the seed data from DataInitializer, then repairs it. Uses its own in-memory
 * database: the default one is shared by every test context in the JVM, and repairing it would
 * remove the seed rows other test classes rely on.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seed_repair_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeedDataRepairServiceTests {

    @Autowired
    private SeedDataRepairService repairService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private TMDBClient tmdbClient;

    @Test
    void dryRunChangesNothingThenRepairRenamesPeopleAndRemovesSeedRows() {
        when(tmdbClient.getPerson(anyLong())).thenAnswer(inv -> {
            TMDBPersonDTO dto = new TMDBPersonDTO();
            dto.setId(inv.getArgument(0));
            dto.setName("Real Person" + inv.getArgument(0));
            dto.setGender(2);
            // Longer than the 1000-char biography column, as real TMDB biographies often are
            dto.setBiography("Real biography. " + "word ".repeat(400));
            // Person 1's IMDb id is already used by another row, so it must not be copied
            dto.setImdbId(((Long) inv.getArgument(0)) == 1L ? "nm0000184" : "nm" + inv.getArgument(0));
            return dto;
        });

        // A "real" credit reusing the seed character Hermione Granger (characters are
        // deduplicated by full name), on a non-seed movie and person.
        jdbc.update("INSERT INTO movies (title, tmdb_movie_id, created_at, updated_at) VALUES ('Real Movie', 999, NOW(), NOW())");
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, tmdb_person_id, created_at, updated_at) "
            + "VALUES ('Emma', 'Watson', 'Emma Watson', 10990, NOW(), NOW())");
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, imdb_id, created_at, updated_at) "
            + "VALUES ('George', 'Lucas', 'George Lucas', 'nm0000184', NOW(), NOW())");
        jdbc.update("INSERT INTO credits (movie_id, person_id, character_id, role_type, department, job, created_at) "
            + "SELECT m.id, p.id, c.id, 'CAST', 'Acting', 'Actor', NOW() FROM movies m, people p, characters c "
            + "WHERE m.tmdb_movie_id = 999 AND p.tmdb_person_id = 10990 AND c.full_name = 'Hermione Granger'");

        Map<String, Object> dryRun = repairService.repair(true);
        assertThat((List<?>) dryRun.get("people")).hasSize(11);
        assertThat((List<?>) dryRun.get("moviesDeleted")).hasSize(7);
        assertThat(count("SELECT COUNT(*) FROM movies WHERE tmdb_movie_id BETWEEN 1001 AND 1007")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM people WHERE biography LIKE 'Sample biography for %'")).isEqualTo(11);

        repairService.repair(false);

        assertThat(count("SELECT COUNT(*) FROM people WHERE biography LIKE 'Sample biography for %'")).isZero();
        assertThat(jdbc.queryForObject("SELECT full_name FROM people WHERE tmdb_person_id = 1", String.class))
            .isEqualTo("Real Person1");
        assertThat(jdbc.queryForObject("SELECT LENGTH(biography) FROM people WHERE tmdb_person_id = 1", Integer.class))
            .isLessThanOrEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT imdb_id FROM people WHERE tmdb_person_id = 1", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT imdb_id FROM people WHERE tmdb_person_id = 2", String.class)).isEqualTo("nm2");
        assertThat(count("SELECT COUNT(*) FROM movies WHERE tmdb_movie_id BETWEEN 1001 AND 1007")).isZero();
        assertThat(count("SELECT COUNT(*) FROM movies WHERE tmdb_movie_id = 999")).isEqualTo(1);
        // Hermione is still used by the real credit; the other seed characters are gone.
        assertThat(count("SELECT COUNT(*) FROM characters WHERE full_name = 'Hermione Granger'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM characters WHERE full_name = 'Mia Dolan'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM credits")).isEqualTo(1);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}

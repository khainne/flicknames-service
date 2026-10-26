package com.flicknames.service.collector.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses its own in-memory database, so re-splitting names does not touch other test classes' rows. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:person_name_repair_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonNameRepairServiceTests {

    @Autowired
    private PersonNameRepairService repairService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dryRunCountsThenRepairResplitsOldNames() {
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, tmdb_person_id, created_at, updated_at) "
            + "VALUES ('Samuel L.', 'Jackson', 'Samuel L. Jackson', 900001, NOW(), NOW())");
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, tmdb_person_id, created_at, updated_at) "
            + "VALUES ('Robert Downey', 'Jr.', 'Robert Downey Jr.', 900002, NOW(), NOW())");

        Map<String, Object> dry = repairService.repair(true);
        assertThat((long) dry.get("changed")).isGreaterThanOrEqualTo(2);
        assertThat(name(900001)).isEqualTo("Samuel L.|Jackson");

        repairService.repair(false);
        assertThat(name(900001)).isEqualTo("Samuel|Jackson");
        assertThat(name(900002)).isEqualTo("Robert|Downey Jr.");
        assertThat((long) repairService.repair(true).get("changed")).isZero();
    }

    private String name(long tmdbId) {
        return jdbc.queryForObject("SELECT first_name || '|' || last_name FROM people WHERE tmdb_person_id = ?",
            String.class, tmdbId);
    }
}

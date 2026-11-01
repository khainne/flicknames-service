package com.flicknames.service;

import com.flicknames.service.service.MovieNamesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /movies/{id}/names on DataInitializer's La La Land: Emma Stone, Ryan Gosling, and Damien Chazelle twice in the crew.
 * The seed catalogue is tiny, so debuts are claimed from one film a year here; MovieNamesThinCatalogTests covers the default.
 */
@SpringBootTest(properties = "flicknames.movie-names.debut-min-films=1")
@AutoConfigureMockMvc
@Transactional
class MovieNamesTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovieNamesService movieNames;

    private long laLaLand;

    @BeforeEach
    void seed() {
        movieNames.clearCache();
        laLaLand = jdbc.queryForObject("SELECT id FROM movies WHERE title = 'La La Land'", Long.class);
        // US births; Damien is left unregistered
        given("Emma", 1_000_000);
        given("Ryan", 600_000);
        given("Mia", 300_000);
        given("Sebastian", 50_000);
    }

    @Test
    void everyPersonCountsOnceAndUnregisteredNamesAreTiered() throws Exception {
        mvc.perform(get("/api/v1/movies/" + laLaLand + "/names"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("La La Land"))
            .andExpect(jsonPath("$.summary.people").value(3))
            .andExpect(jsonPath("$.summary.distinctNames").value(3))
            .andExpect(jsonPath("$.summary.variety").value(1.0))
            .andExpect(jsonPath("$.summary.tiers.NEVER_REGISTERED").value(1))
            .andExpect(jsonPath("$.summary.neverRegisteredNames").value(1))
            .andExpect(jsonPath("$.names[?(@.name == 'Damien')].tier").value("NEVER_REGISTERED"))
            .andExpect(jsonPath("$.names[?(@.name == 'Damien')].people").value(1))
            .andExpect(jsonPath("$.names[?(@.name == 'Damien')].crew").value(true))
            .andExpect(jsonPath("$.names[?(@.name == 'Emma')].tier").value("EVERYDAY"))
            .andExpect(jsonPath("$.names[?(@.name == 'Emma')].usBirths").value(1_000_000));
    }

    @Test
    void debutIsTheFirstFilmWithTheNameAsPersonOrCharacter() throws Exception {
        // Emma Watson was in Harry Potter (2011) and Ryan Reynolds in Deadpool (Feb 2016), both before La La Land
        mvc.perform(get("/api/v1/movies/" + laLaLand + "/names"))
            .andExpect(jsonPath("$.names[?(@.name == 'Emma')].debut").value(false))
            .andExpect(jsonPath("$.names[?(@.name == 'Emma')].firstSeen").value("2011-07-15"))
            .andExpect(jsonPath("$.names[?(@.name == 'Ryan')].debut").value(false))
            .andExpect(jsonPath("$.names[?(@.name == 'Damien')].debut").value(true))
            .andExpect(jsonPath("$.summary.debutNames").value(1))
            .andExpect(jsonPath("$.characters[?(@.firstName == 'Mia')].debut").value(true))
            .andExpect(jsonPath("$.characters[?(@.firstName == 'Mia')].actor").value("Emma Stone"));
    }

    @Test
    void releaseBirthsCoverCharactersLeadsAndDirectorsWithBirthsAroundRelease() throws Exception {
        // La La Land: December 2016; SSA data seeded 2014-2018 for Mia and Emma only
        ssa("Mia", "F", 2014, 1000, 2018);
        ssa("Emma", "F", 2014, 500, 2018);
        mvc.perform(get("/api/v1/movies/" + laLaLand + "/names"))
            .andExpect(jsonPath("$.releaseBirths[0].name").value("Mia"))
            .andExpect(jsonPath("$.releaseBirths[0].role").value("CHARACTER"))
            .andExpect(jsonPath("$.releaseBirths[0].who").value("Mia Dolan · Emma Stone"))
            .andExpect(jsonPath("$.releaseBirths[0].births.length()").value(5))
            .andExpect(jsonPath("$.releaseBirths[0].births[0].year").value(2014))
            .andExpect(jsonPath("$.releaseBirths[0].births[4].count").value(1004))
            .andExpect(jsonPath("$.releaseBirths[1].name").value("Emma"))
            .andExpect(jsonPath("$.releaseBirths[1].role").value("LEAD"))
            .andExpect(jsonPath("$.releaseBirths.length()").value(2));
    }

    @Test
    void unknownFilmIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/movies/999999/names")).andExpect(status().isNotFound());
    }

    /** SSA rows for a name from {@code from} to {@code to}: base, base+1, ... babies. */
    private void ssa(String name, String sex, int from, int base, int to) {
        jdbc.update("INSERT INTO ssa_names (name, sex, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)", name, sex);
        Long id = jdbc.queryForObject("SELECT id FROM ssa_names WHERE name = ? AND sex = ?", Long.class, name, sex);
        for (int y = from; y <= to; y++) {
            jdbc.update("INSERT INTO ssa_name_yearly_stats (ssa_name_id, year, count, created_at) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)", id, y, base + (y - from));
        }
    }

    private void given(String spelling, long births) {
        jdbc.update("DELETE FROM given_names WHERE spelling = ?", spelling);
        jdbc.update("INSERT INTO given_names (spelling, folded, people_count, character_count, ssa_male, ssa_female, "
            + "people_male, people_female, compound, quality) VALUES (?, ?, 1, 1, 0, ?, 0, 1, FALSE, 'LEXICON')",
            spelling, spelling.toLowerCase(), births);
    }
}

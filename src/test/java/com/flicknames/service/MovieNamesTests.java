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

/** /movies/{id}/names on DataInitializer's La La Land: Emma Stone, Ryan Gosling, and Damien Chazelle twice in the crew. */
@SpringBootTest
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
    void unknownFilmIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/movies/999999/names")).andExpect(status().isNotFound());
    }

    private void given(String spelling, long births) {
        jdbc.update("DELETE FROM given_names WHERE spelling = ?", spelling);
        jdbc.update("INSERT INTO given_names (spelling, folded, people_count, character_count, ssa_male, ssa_female, "
            + "people_male, people_female, compound, quality) VALUES (?, ?, 1, 1, 0, ?, 0, 1, FALSE, 'LEXICON')",
            spelling, spelling.toLowerCase(), births);
    }
}

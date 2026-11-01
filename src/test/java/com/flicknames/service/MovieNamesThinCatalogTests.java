package com.flicknames.service;

import com.flicknames.service.service.MovieNamesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the default threshold, the seed catalogue's one film from 2016 is too few to claim screen debuts. */
@SpringBootTest
@AutoConfigureMockMvc
class MovieNamesThinCatalogTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovieNamesService movieNames;

    @BeforeEach
    void clear() {
        movieNames.clearCache();
    }

    @Test
    void debutsAreNotClaimedForYearsTheCatalogueBarelyCovers() throws Exception {
        long laLaLand = jdbc.queryForObject("SELECT id FROM movies WHERE title = 'La La Land'", Long.class);
        mvc.perform(get("/api/v1/movies/" + laLaLand + "/names"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.debutsReliable").value(false))
            .andExpect(jsonPath("$.catalogFilmsInYear").value(2))
            .andExpect(jsonPath("$.summary.debutNames").value(0))
            .andExpect(jsonPath("$.names[?(@.name == 'Damien')].debut").value(false))
            .andExpect(jsonPath("$.names[?(@.name == 'Damien')].firstSeen").value("2016-12-09"));
    }
}

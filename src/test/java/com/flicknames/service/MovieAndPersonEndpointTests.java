package com.flicknames.service;

import com.flicknames.service.entity.NameTypeConverter;
import com.flicknames.service.entity.ScreenCharacter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the seed data from DataInitializer. */
@SpringBootTest
@AutoConfigureMockMvc
class MovieAndPersonEndpointTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void movieCreditsSurviveLegacyNameTypeValues() throws Exception {
        Long movieId = jdbc.queryForObject("SELECT id FROM movies WHERE title = 'La La Land'", Long.class);
        jdbc.update("UPDATE characters SET name_type = 'SSA_UNVERIFIED' WHERE first_name = 'Mia'");
        try {
            mvc.perform(get("/api/v1/movies/" + movieId + "/credits"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.movie.title").value("La La Land"))
                    .andExpect(jsonPath("$.cast[*].character", hasItem("Mia Dolan")));
        } finally {
            jdbc.update("UPDATE characters SET name_type = 'STANDARD' WHERE first_name = 'Mia'");
        }
    }

    @Test
    void movieSearchPutsExactTitleThenBiggestBoxOfficeFirst() throws Exception {
        String insert = "INSERT INTO movies (title, release_date, revenue, created_at, updated_at) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        jdbc.update(insert, "Zorblax & Friends: Recipe for Fun", java.sql.Date.valueOf("2025-03-06"), 0);
        jdbc.update(insert, "Mini Zorblax", java.sql.Date.valueOf("2024-01-01"), null);
        jdbc.update(insert, "Zorblax Returns", java.sql.Date.valueOf("2019-05-01"), 250_000_000);
        jdbc.update(insert, "The Zorblax", java.sql.Date.valueOf("2023-07-19"), 1_400_000_000);
        try {
            mvc.perform(get("/api/v1/movies/search").param("q", " zorblax "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.content[0].title").value("The Zorblax"))
                    .andExpect(jsonPath("$.content[1].title").value("Zorblax Returns"))
                    .andExpect(jsonPath("$.content[2].title").value("Zorblax & Friends: Recipe for Fun"))
                    .andExpect(jsonPath("$.content[3].title").value("Mini Zorblax"));
            // LIKE wildcards in the query are matched literally
            mvc.perform(get("/api/v1/movies/search").param("q", "zorbla_"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        } finally {
            jdbc.update("DELETE FROM movies WHERE LOWER(title) LIKE '%zorblax%'");
        }
    }

    @Test
    void unknownMovieOrPersonIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/movies/999999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/movies/999999999/credits")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/people/999999999")).andExpect(status().isNotFound());
    }

    @Test
    void nameTypeConverterFallsBackToUnknown() {
        NameTypeConverter converter = new NameTypeConverter();
        assertThat(converter.convertToEntityAttribute("STANDARD")).isEqualTo(ScreenCharacter.NameType.STANDARD);
        assertThat(converter.convertToEntityAttribute("SSA_UNVERIFIED")).isEqualTo(ScreenCharacter.NameType.UNKNOWN);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}

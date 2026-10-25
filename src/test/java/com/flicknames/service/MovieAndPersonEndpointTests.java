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

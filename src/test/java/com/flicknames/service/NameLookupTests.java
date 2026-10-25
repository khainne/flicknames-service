package com.flicknames.service;

import com.flicknames.service.entity.ReviewStatusConverter;
import com.flicknames.service.entity.ScreenCharacter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the seed data from DataInitializer (Emma Stone, Bella Baxter, ...). */
@SpringBootTest
@AutoConfigureMockMvc
class NameLookupTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void personNameLookupIsCaseInsensitive() throws Exception {
        mvc.perform(get("/api/v1/names/emma/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Emma"))
                .andExpect(jsonPath("$.peopleCount").value(3));
        mvc.perform(get("/api/v1/names/EMMA/people"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/professions/for-name/emma"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Actor")));
    }

    @Test
    void characterNameLookupIsCaseInsensitive() throws Exception {
        mvc.perform(get("/api/v1/character-names/bella/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bella"));
        mvc.perform(get("/api/v1/character-names/bella/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Bella Baxter"));
    }

    @Test
    void unknownNameIsNotFoundRatherThanServerError() throws Exception {
        mvc.perform(get("/api/v1/names/Zzyzx/stats")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/character-names/Zzyzx/stats")).andExpect(status().isNotFound());
    }

    @Test
    void characterStatsUseTheCharactersOwnMovies() throws Exception {
        // Previously credits were looked up by person id using the character's id,
        // so a character got some unrelated person's filmography.
        mvc.perform(get("/api/v1/character-names/Bella/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topMovies[*].title", hasItem("Poor Things")))
                .andExpect(jsonPath("$.topMovies.length()").value(1));
    }

    @Test
    void trendingSurvivesLegacyReviewStatusValues() throws Exception {
        jdbc.update("UPDATE characters SET review_status = 'FLAGGED_BAD_PARSE' WHERE first_name = 'Bella'");
        jdbc.update("UPDATE characters SET review_status = 'SOMETHING_ELSE' WHERE first_name = 'Felicity'");
        try {
            mvc.perform(get("/api/v1/all-names/trending/yearly/2023"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name", hasItem("Emma")))
                    .andExpect(jsonPath("$[*].name", hasItem("Bella")));
            mvc.perform(get("/api/v1/character-names/trending/yearly/2023"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name", not(hasItem("Mia"))));
        } finally {
            jdbc.update("UPDATE characters SET review_status = 'PENDING_REVIEW' WHERE first_name IN ('Bella', 'Felicity')");
        }
    }

    @Test
    void reviewStatusConverterMapsLegacyAndUnknownValues() {
        ReviewStatusConverter converter = new ReviewStatusConverter();
        assertThat(converter.convertToEntityAttribute("APPROVED")).isEqualTo(ScreenCharacter.ReviewStatus.APPROVED);
        assertThat(converter.convertToEntityAttribute("FLAGGED_BAD_PARSE")).isEqualTo(ScreenCharacter.ReviewStatus.INCORRECT_PARSE);
        assertThat(converter.convertToEntityAttribute("NOT_A_STATUS")).isEqualTo(ScreenCharacter.ReviewStatus.FLAGGED);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(ScreenCharacter.ReviewStatus.NO_FIRST_NAME)).isEqualTo("NO_FIRST_NAME");
    }
}

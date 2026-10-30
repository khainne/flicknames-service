package com.flicknames.service;

import com.flicknames.service.service.NamePopularityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Popularity, cast and appearances endpoints; credits come from DataInitializer's seed data. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NamePageTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamePopularityService popularity;

    @BeforeEach
    void seedSsa() {
        popularity.clearCache();
        // Ranks are left empty, as in production before the ranking job runs, so they are worked out from counts
        long olivia = name("Olivia", "F");
        long emma = name("Emma", "F");
        long amelia = name("Amelia", "F");
        long aurelia = name("Aurelia", "F");
        year(olivia, 2014, 19836);
        year(olivia, 2019, 18569);
        long olivia2024 = year(olivia, 2024, 14718);
        year(emma, 2019, 17000);
        long emma2024 = year(emma, 2024, 13000);
        year(amelia, 2019, 11000);
        year(amelia, 2024, 12000);
        year(aurelia, 2019, 400);
        year(aurelia, 2024, 900);
        state(olivia2024, "CA", 2000);
        state(emma2024, "CA", 2100);
        state(olivia2024, "TX", 1500);
        state(emma2024, "TX", 1000);
    }

    @Test
    void popularityHasEveryYearWithRanksWorkedOutFromCounts() throws Exception {
        mvc.perform(get("/api/v1/names/OLIVIA/popularity"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Olivia"))
            .andExpect(jsonPath("$.sex").value("F"))
            .andExpect(jsonPath("$.latestYear").value(2024))
            .andExpect(jsonPath("$.years[*].year", contains(2014, 2019, 2024)))
            .andExpect(jsonPath("$.years[2].count").value(14718))
            .andExpect(jsonPath("$.years[2].rank").value(1))
            .andExpect(jsonPath("$.years[1].rank").value(1));
    }

    @Test
    void popularityHasShareOfBirthsAndStateRanksWithLeaders() throws Exception {
        // 14,718 of 40,618 girls in the seed data
        mvc.perform(get("/api/v1/names/Olivia/popularity"))
            .andExpect(jsonPath("$.share.births").value(40618))
            .andExpect(jsonPath("$.share.oneIn").value(3))
            .andExpect(jsonPath("$.states.year").value(2024))
            .andExpect(jsonPath("$.states.states[?(@.state == 'CA')].rank").value(2))
            .andExpect(jsonPath("$.states.states[?(@.state == 'CA')].leader").value("Emma"))
            .andExpect(jsonPath("$.states.states[?(@.state == 'TX')].rank").value(1))
            .andExpect(jsonPath("$.states.states[?(@.state == 'TX')].leader").value("Olivia"));
    }

    @Test
    void risingNamesShareASoundAndGrewFromAMeaningfulBase() throws Exception {
        // Aurelia (-ia, 400 → 900) qualifies; Amelia (-ia) grew under 20%; Emma shares no letters
        mvc.perform(get("/api/v1/names/Olivia/popularity"))
            .andExpect(jsonPath("$.rising[*].name", contains("Aurelia")))
            .andExpect(jsonPath("$.rising[0].earlierYear").value(2019))
            .andExpect(jsonPath("$.rising[0].earlierCount").value(400));
    }

    @Test
    void popularityOfANameTheSsaNeverRecordedIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/names/Zzyzx/popularity")).andExpect(status().isNotFound());
    }

    @Test
    void castListsActingRolesBiggestFilmsFirst() throws Exception {
        mvc.perform(get("/api/v1/names/emma/cast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].actor.fullName").value("Emma Watson"))
            .andExpect(jsonPath("$[0].character").value("Hermione Granger"))
            .andExpect(jsonPath("$[0].movie.title").value("Harry Potter and the Deathly Hallows: Part 2"))
            .andExpect(jsonPath("$[*].character", everyItem(not(nullValue()))));
    }

    @Test
    void castLeavesOutCrewCredits() throws Exception {
        // Olivia Wilde only directs and produces in the seed data
        mvc.perform(get("/api/v1/names/Olivia/cast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.actor.fullName == 'Olivia Wilde')]", empty()));
    }

    @Test
    void appearancesGiveEachCharacterItsFilmAndActor() throws Exception {
        mvc.perform(get("/api/v1/character-names/bella/appearances"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].fullName").value("Bella Baxter"))
            .andExpect(jsonPath("$[0].movie.title").value("Poor Things"))
            .andExpect(jsonPath("$[0].actor.fullName").value("Emma Stone"));
    }

    private long name(String name, String sex) {
        jdbc.update("INSERT INTO ssa_names (name, sex, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)", name, sex);
        return jdbc.queryForObject("SELECT id FROM ssa_names WHERE name = ? AND sex = ?", Long.class, name, sex);
    }

    private long year(long nameId, int year, int count) {
        jdbc.update("INSERT INTO ssa_name_yearly_stats (ssa_name_id, year, count, created_at) "
            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)", nameId, year, count);
        return jdbc.queryForObject("SELECT id FROM ssa_name_yearly_stats WHERE ssa_name_id = ? AND year = ?",
            Long.class, nameId, year);
    }

    private void state(long yearlyStatId, String state, int count) {
        jdbc.update("INSERT INTO ssa_name_state_breakdowns (yearly_stat_id, state_code, count, created_at) "
            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)", yearlyStatId, state, count);
    }
}

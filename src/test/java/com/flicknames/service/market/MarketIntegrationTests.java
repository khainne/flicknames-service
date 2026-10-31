package com.flicknames.service.market;

import com.flicknames.service.collector.client.TMDBClient;
import com.flicknames.service.collector.dto.TMDBMovieDTO;
import com.flicknames.service.config.AdminApiKeyFilter;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.repository.ScreenCharacterRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two weeks of a tiny market. Last week only "Big Film" (Michael lead, playing Ana) was in the
 * window; this week "New Film" (Kai lead, playing Kai) opens too, so Kai gains and Michael loses
 * share. Uses its own in-memory database so its rows don't leak into other test classes.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:market_test;DB_CLOSE_DELAY=-1",
    "admin.api-key=test-key"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketIntegrationTests {

    /** A Wednesday: this week starts Monday 2026-10-26, last week 2026-10-19. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate THIS_WEEK = LocalDate.of(2026, 10, 26);

    @Autowired private NameIndexService indexService;
    @Autowired private MarketService marketService;
    @Autowired private RevenueRefreshService revenueRefreshService;
    @Autowired private MovieRevenueSnapshotRepository snapshotRepository;
    @Autowired private MovieRepository movies;
    @Autowired private PersonRepository people;
    @Autowired private ScreenCharacterRepository characters;
    @Autowired private CreditRepository credits;
    @Autowired private MockMvc mvc;

    @MockBean private TMDBClient tmdbClient;

    private Movie bigFilm;
    private Movie newFilm;

    @BeforeAll
    void seed() {
        bigFilm = movies.save(Movie.builder().title("Big Film").tmdbMovieId(880_001L)
            .releaseDate(LocalDate.of(2026, 10, 1)).revenue(new BigDecimal("1000")).build());
        newFilm = movies.save(Movie.builder().title("New Film").tmdbMovieId(880_002L)
            .releaseDate(LocalDate.of(2026, 10, 27)).revenue(new BigDecimal("500")).build());
        Person michaelLead = person("Michael", "Lead");
        Person michaelMixer = person("Michael", "Mixer");
        Person kaiStar = person("Kai", "Star");
        ScreenCharacter ana = character("Ana", "Ana Smith");
        ScreenCharacter kaiHero = character("Kai", "Kai Hero");

        credit(bigFilm, michaelLead, ana, Credit.RoleType.CAST, "Acting", "Actor", 1);
        credit(bigFilm, michaelMixer, null, Credit.RoleType.CREW, "Sound", "Sound Mixer", null);
        credit(newFilm, kaiStar, kaiHero, Credit.RoleType.CAST, "Acting", "Actor", 1);

        Map<String, Object> report = indexService.rebuild(LocalDate.of(2026, 10, 19), TODAY, TODAY);
        assertThat(report.get("weeks")).isEqualTo(2);
        assertThat(report.get("done")).isEqualTo(true);
    }

    @Test
    void peopleMarketShowsKaiGainingAndMichaelLosing() {
        MarketDtos.Movers movers = marketService.movers(Market.PERSON, 10, TODAY).orElseThrow();
        assertThat(movers.weekStart()).isEqualTo(THIS_WEEK);
        assertThat(movers.asOf()).isEqualTo(TODAY);

        // This week: Michael 1000x3 = 3000, Kai 500x3 = 1500. The mixer doesn't add to Michael's film.
        assertThat(movers.top()).extracting(MarketDtos.Quote::name).containsExactly("Michael", "Kai");
        MarketDtos.Quote michael = movers.top().get(0);
        assertThat(michael.index()).isCloseTo(66.667, within(0.001));
        assertThat(michael.rank()).isEqualTo(1);
        assertThat(michael.change1w()).isCloseTo(-33.333, within(0.001));
        assertThat(michael.pctChange1w()).isCloseTo(-33.333, within(0.001));
        assertThat(michael.sparkline()).hasSize(MarketService.SPARKLINE_WEEKS);
        assertThat(michael.sparkline().get(MarketService.SPARKLINE_WEEKS - 2)).isEqualTo(100.0);
        assertThat(michael.drivers()).singleElement().satisfies(d -> {
            assertThat(d.movieTitle()).isEqualTo("Big Film");
            assertThat(d.fullName()).isEqualTo("Michael Lead");
            assertThat(d.role()).isEqualTo("as Ana Smith");
        });

        assertThat(movers.gainers()).extracting(MarketDtos.Quote::name).containsExactly("Kai");
        assertThat(movers.gainers().get(0).pctChange1w()).isNull(); // new this week
        assertThat(movers.losers()).extracting(MarketDtos.Quote::name).containsExactly("Michael");
    }

    @Test
    void characterMarketNamesTheActor() {
        MarketDtos.Movers movers = marketService.movers(Market.CHARACTER, 10, TODAY).orElseThrow();
        assertThat(movers.top()).extracting(MarketDtos.Quote::name).containsExactly("Ana", "Kai");
        MarketDtos.Driver kai = movers.gainers().get(0).drivers().get(0);
        assertThat(kai.fullName()).isEqualTo("Kai Hero");
        assertThat(kai.playedBy()).isEqualTo("Kai Star");
    }

    @Test
    void historyFillsUnrankedWeeksWithZero() throws Exception {
        mvc.perform(get("/api/v1/market/compare")
                .param("names", "Michael, kai ,Nobody")
                .param("from", "2026-10-12").param("to", "2026-10-28"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.michael.name").value("Michael"))
            .andExpect(jsonPath("$.michael.points.length()").value(3))
            .andExpect(jsonPath("$.michael.points[0].index").value(0.0))
            .andExpect(jsonPath("$.michael.points[0].rank").doesNotExist())
            .andExpect(jsonPath("$.michael.points[1].index").value(100.0))
            .andExpect(jsonPath("$.kai.points[2].rank").value(2))
            .andExpect(jsonPath("$.nobody.points[2].index").value(0.0));

        mvc.perform(get("/api/v1/market/names/Kai/history").param("market", "CHARACTER")
                .param("from", "2026-10-26"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.market").value("CHARACTER"))
            .andExpect(jsonPath("$.points[0].rank").value(2));
    }

    @Test
    void monthlyHistoryAveragesWeeksAndKeepsTheBestRank() throws Exception {
        // October weeks: 10-05 and 10-12 unranked (0), 10-19 at 100, 10-26 at 66.667.
        mvc.perform(get("/api/v1/market/compare").param("names", "Michael").param("interval", "MONTH")
                .param("from", "2026-10-01").param("to", "2026-10-28"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.michael.points.length()").value(2))
            .andExpect(jsonPath("$.michael.points[0].week").value("2026-09-01"))
            .andExpect(jsonPath("$.michael.points[0].index").value(0.0))
            .andExpect(jsonPath("$.michael.points[1].week").value("2026-10-01"))
            .andExpect(jsonPath("$.michael.points[1].index").value(41.667))
            .andExpect(jsonPath("$.michael.points[1].rank").value(1));
    }

    @Test
    void heatmapRanksNamesByAverageOverTheRange() throws Exception {
        mvc.perform(get("/api/v1/market/heatmap").param("from", "2026-10-19").param("to", "2026-10-28"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interval").value("MONTH"))
            .andExpect(jsonPath("$.periods[0]").value("2026-10-01"))
            .andExpect(jsonPath("$.rows[0].name").value("Michael"))
            .andExpect(jsonPath("$.rows[0].average").value(83.333))
            .andExpect(jsonPath("$.rows[0].values[0]").value(closeTo(83.333, 0.002))) // averaged from rounded weeks
            .andExpect(jsonPath("$.rows[1].name").value("Kai"));
        mvc.perform(get("/api/v1/market/heatmap").param("from", "2026-10-28").param("to", "2026-10-01"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rebuildAndRefreshNeedTheAdminKey() throws Exception {
        mvc.perform(post("/api/v1/admin/market/rebuild").param("from", "2026-10-26"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/market/refresh-revenue"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/market/refresh-revenue").header(AdminApiKeyFilter.HEADER, "test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dryRun").value(true));
    }

    @Test
    void revenueRefreshUpdatesChangedFilmsAndSnapshotsOncePerChange() {
        when(tmdbClient.getMovie(anyLong())).thenAnswer(inv -> {
            TMDBMovieDTO dto = new TMDBMovieDTO();
            long id = inv.getArgument(0);
            dto.setRevenue(id == 880_002L ? 2_000L : 1_000L);
            dto.setPosterPath("/p" + id + ".jpg");
            return dto;
        });

        Map<String, Object> dry = revenueRefreshService.refresh(60, true, TODAY);
        assertThat(dry.get("revenueChanged")).isEqualTo(1);
        assertThat(movies.findById(newFilm.getId()).orElseThrow().getRevenue()).isEqualByComparingTo("500");

        Map<String, Object> applied = revenueRefreshService.refresh(60, false, TODAY);
        assertThat(applied.get("snapshots")).isEqualTo(2);
        Movie refreshed = movies.findById(newFilm.getId()).orElseThrow();
        assertThat(refreshed.getRevenue()).isEqualByComparingTo("2000");
        assertThat(refreshed.getPosterPath()).isEqualTo("/p880002.jpg");

        Map<String, Object> again = revenueRefreshService.refresh(60, false, TODAY);
        assertThat(again.get("snapshots")).isEqualTo(0);
        assertThat(again.get("revenueChanged")).isEqualTo(0);
        assertThat(snapshotRepository.findAll()).hasSize(2);
    }

    private Person person(String first, String last) {
        return people.save(Person.builder().firstName(first).lastName(last).fullName(first + " " + last).build());
    }

    private ScreenCharacter character(String first, String full) {
        return characters.save(ScreenCharacter.builder().firstName(first).fullName(full)
            .nameType(ScreenCharacter.NameType.STANDARD).build());
    }

    private void credit(Movie movie, Person person, ScreenCharacter character, Credit.RoleType type,
                        String department, String job, Integer order) {
        credits.save(Credit.builder().movie(movie).person(person).character(character).roleType(type)
            .department(department).job(job).order(order).build());
    }

}

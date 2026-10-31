package com.flicknames.service.labels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.config.AdminApiKeyFilter;
import com.flicknames.service.entity.CharacterNameLabel.Verdict;
import com.flicknames.service.entity.Credit;
import com.flicknames.service.entity.Movie;
import com.flicknames.service.entity.Person;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.repository.CharacterNameLabelRepository;
import com.flicknames.service.repository.CreditRepository;
import com.flicknames.service.repository.MovieRepository;
import com.flicknames.service.repository.PersonRepository;
import com.flicknames.service.repository.ScreenCharacterRepository;
import com.flicknames.service.service.CharacterNameLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Labels write to the database, so this class runs on its own H2 database (not the shared
 * flicknamesdb that other test classes read), still seeded by DataInitializer.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:name-labels-tests;DB_CLOSE_DELAY=-1",
        "admin.api-key=test-key",
        // A tiny ranking window so every queue test also exercises the window growing as labels use it up.
        "labels.queue.initial-window=2",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CharacterNameLabelTests {

    private static final String BASE = "/api/v1/admin/character-name-labels";
    private static final String KEY = "test-key";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CharacterNameLabelRepository labels;
    @Autowired private CharacterNameLabelService labelService;
    @Autowired private ScreenCharacterRepository characters;
    @Autowired private MovieRepository movies;
    @Autowired private PersonRepository people;
    @Autowired private CreditRepository credits;

    @BeforeEach
    void resetLabels() {
        labels.deleteAll();
        if (characters.findByFullName("Test Himself").isEmpty()) {
            Movie oppenheimer = movie("Oppenheimer");
            Movie potter = movie("Harry Potter and the Deathly Hallows: Part 2");
            ScreenCharacter himself = character("Test Himself", "Test", "Himself", NameType.STANDARD);
            // Two credits in one movie must count that movie's revenue once.
            credit(person("Emma", "Stone"), oppenheimer, himself, 10);
            credit(person("Ryan", "Gosling"), oppenheimer, himself, 11);
            credit(person("Emma", "Watson"), potter, himself, 12);
        }
    }

    // ------------------------------------------------------------------ queue

    @Test
    void queueRanksByDistinctMovieRevenueAndDropsLabeledStrings() throws Exception {
        JsonNode page = getJson(BASE + "/queue?limit=50");
        JsonNode items = page.get("items");
        assertThat(items.size()).isGreaterThan(3);

        JsonNode top = items.get(0);
        assertThat(top.get("fullName").asText()).isEqualTo("Test Himself");
        assertThat(new BigDecimal(top.get("totalRevenue").asText())).isEqualByComparingTo("2294000000");
        assertThat(top.get("movieCount").asLong()).isEqualTo(2);
        assertThat(top.get("creditCount").asLong()).isEqualTo(3);
        assertThat(top.get("firstName").asText()).isEqualTo("Test");
        assertThat(top.get("nameType").asText()).isEqualTo("STANDARD");
        assertThat(top.get("characterId").asLong()).isPositive();
        JsonNode examples = top.get("examples");
        assertThat(examples.size()).isEqualTo(2);
        assertThat(examples.get(0).get("title").asText()).isEqualTo("Harry Potter and the Deathly Hallows: Part 2");
        assertThat(examples.get(0).get("year").asInt()).isEqualTo(2011);
        assertThat(examples.get(0).get("actorName").asText()).isEqualTo("Emma Watson");
        assertThat(examples.get(1).get("title").asText()).isEqualTo("Oppenheimer");

        BigDecimal previous = null;
        for (JsonNode item : items) {
            BigDecimal revenue = new BigDecimal(item.get("totalRevenue").asText());
            if (previous != null) {
                assertThat(revenue).isLessThanOrEqualTo(previous);
            }
            previous = revenue;
        }

        // Skipping is enough to leave the queue; so is any other verdict.
        save("Test Himself", "SKIPPED", null).andExpect(status().isCreated());
        save("Mia Dolan", "NAME", "Mia").andExpect(status().isCreated());
        List<String> after = names(getJson(BASE + "/queue?limit=50"));
        assertThat(after).doesNotContain("Test Himself", "Mia Dolan").contains("Hermione Granger");
        assertThat(after.get(0)).isEqualTo("Hermione Granger");
    }

    @Test
    void queueRankingIsCachedButRefreshable() throws Exception {
        getJson(BASE + "/queue?q=cacheprobe");
        ScreenCharacter probe = character("CacheProbe", "Cache", "Probe", NameType.STANDARD);
        credit(person("Florence", "Pugh"), movie("Poor Things"), probe, 20);
        // An empty ranking is never cached, so the new string shows up straight away...
        assertThat(names(getJson(BASE + "/queue?q=cacheprobe"))).containsExactly("CacheProbe");

        ScreenCharacter probe2 = character("CacheProbe Two", "Cache", "Two", NameType.STANDARD);
        credit(person("Florence", "Pugh"), movie("Poor Things"), probe2, 21);
        // ...but a non-empty one is, until refresh=true.
        assertThat(names(getJson(BASE + "/queue?q=cacheprobe"))).containsExactly("CacheProbe");
        assertThat(names(getJson(BASE + "/queue?q=cacheprobe&refresh=true"))).containsExactlyInAnyOrder("CacheProbe", "CacheProbe Two");
    }

    @Test
    void queuePagesAndFilters() throws Exception {
        JsonNode first = getJson(BASE + "/queue?limit=2");
        assertThat(first.get("items").size()).isEqualTo(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = getJson(BASE + "/queue?limit=2&offset=2");
        assertThat(names(second)).doesNotContainAnyElementsOf(names(first));

        assertThat(names(getJson(BASE + "/queue?firstName=MIA,sebastian"))).containsExactlyInAnyOrder("Mia Dolan", "Sebastian Wilder");
        assertThat(names(getJson(BASE + "/queue?q=baxt"))).containsExactlyInAnyOrder("Bella Baxter", "Felicity Baxter");
        for (JsonNode item : getJson(BASE + "/queue?hasFirstName=true&limit=100").get("items")) {
            assertThat(item.get("firstName").isNull()).isFalse();
        }
        assertThat(names(getJson(BASE + "/queue?nameType=single_name"))).contains("Abigail").doesNotContain("Mia Dolan");
        mvc.perform(withKey(get(BASE + "/queue?nameType=BOGUS"))).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ writes

    @Test
    void saveUpsertsByFullNameAndDeleteUndoes() throws Exception {
        save("  Young Anna ", "NAME", " Anna ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.label.fullName").value("Young Anna"))
                .andExpect(jsonPath("$.label.firstName").value("Anna"))
                .andExpect(jsonPath("$.matchingCharacters").value(0));

        save("Young Anna", "NOT_A_NAME", "ignored")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.label.verdict").value("NOT_A_NAME"))
                .andExpect(jsonPath("$.label.firstName").doesNotExist());
        assertThat(labels.count()).isEqualTo(1);

        mvc.perform(withKey(get(BASE + "/by-name").param("fullName", "Young Anna")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("NOT_A_NAME"));

        mvc.perform(withKey(delete(BASE).param("fullName", "Young Anna"))).andExpect(status().isOk());
        mvc.perform(withKey(delete(BASE).param("fullName", "Young Anna"))).andExpect(status().isNotFound());
        mvc.perform(withKey(get(BASE + "/by-name").param("fullName", "Young Anna"))).andExpect(status().isNotFound());

        save("Batman / Bruce Wayne", "NAME", "Bruce").andExpect(status().isCreated());
        Long id = labels.findByFullName("Batman / Bruce Wayne").orElseThrow().getId();
        mvc.perform(withKey(delete(BASE + "/" + id))).andExpect(status().isOk());
        assertThat(labels.count()).isZero();
    }

    @Test
    void savingALabelDoesNotTouchCharacters() throws Exception {
        save("Bella Baxter", "NAME", "Isabella").andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchingCharacters").value(1));
        assertThat(characters.findByFullName("Bella Baxter").orElseThrow().getFirstName()).isEqualTo("Bella");
    }

    @Test
    void invalidLabelsAreRejected() throws Exception {
        save("Young Anna", "NAME", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("firstName is required")));
        save("Young Anna", "NAME", "   ").andExpect(status().isBadRequest());
        save("   ", "NOT_A_NAME", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("fullName")));
        save("Young Anna", null, "Anna").andExpect(status().isBadRequest());
        save("Young Anna", "MAYBE", "Anna").andExpect(status().isBadRequest());
        save("x".repeat(256), "NOT_A_NAME", null).andExpect(status().isBadRequest());
        save("Young Anna", "NAME", "A".repeat(101)).andExpect(status().isBadRequest());
        mvc.perform(withKey(post(BASE).contentType(MediaType.APPLICATION_JSON).content("not json")))
                .andExpect(status().isBadRequest());

        List<Map<String, Object>> batch = List.of(body("Ok Name", "NOT_A_NAME", null), body("Bad", "NAME", ""));
        mvc.perform(withKey(post(BASE + "/bulk").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch))))
                .andExpect(status().isBadRequest());
        assertThat(labels.count()).isZero();
    }

    @Test
    void bulkSaveUpsertsAll() throws Exception {
        List<Map<String, Object>> batch = List.of(body("Fedaykin", "NOT_A_NAME", null), body("Young Anna", "NAME", "Anna"));
        mvc.perform(withKey(post(BASE + "/bulk").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(labels.count()).isEqualTo(2);
    }

    @Test
    void writesNeedTheAdminKey() throws Exception {
        String label = json.writeValueAsString(body("Young Anna", "NAME", "Anna"));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(label)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).header(AdminApiKeyFilter.HEADER, "wrong").contentType(MediaType.APPLICATION_JSON).content(label))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/bulk").contentType(MediaType.APPLICATION_JSON).content("[" + label + "]"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE).param("fullName", "Young Anna")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/apply")).andExpect(status().isUnauthorized());
        assertThat(labels.count()).isZero();

        // Reads need the key too.
        mvc.perform(get(BASE + "/queue")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/export.jsonl")).andExpect(status().isUnauthorized());
        mvc.perform(withKey(get(BASE + "/queue"))).andExpect(status().isOk());
        mvc.perform(withKey(get(BASE + "/export.jsonl"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void listAndStats() throws Exception {
        save("Fedaykin", "NOT_A_NAME", null);
        save("Young Anna", "NAME", "Anna");
        save("Peylak", "SKIPPED", null);
        jdbc.update("UPDATE character_name_labels SET updated_at = DATEADD('DAY', -3, updated_at) WHERE full_name = 'Fedaykin'");

        JsonNode list = getJson(BASE + "?size=2");
        assertThat(list.get("totalElements").asLong()).isEqualTo(3);
        assertThat(list.get("labels").size()).isEqualTo(2);
        assertThat(list.get("labels").get(0).get("fullName").asText()).isEqualTo("Peylak");
        assertThat(getJson(BASE + "?verdict=NAME").get("totalElements").asLong()).isEqualTo(1);

        JsonNode stats = getJson(BASE + "/stats");
        assertThat(stats.get("total").asLong()).isEqualTo(3);
        assertThat(stats.get("byVerdict").get("NAME").asLong()).isEqualTo(1);
        assertThat(stats.get("byVerdict").get("NOT_A_NAME").asLong()).isEqualTo(1);
        assertThat(stats.get("byVerdict").get("SKIPPED").asLong()).isEqualTo(1);
        assertThat(stats.get("sinceTotal").asLong()).isEqualTo(2);
        assertThat(stats.get("sinceByVerdict").get("NOT_A_NAME").asLong()).isZero();
        assertThat(stats.get("unapplied").asLong()).isEqualTo(2);

        assertThat(getJson(BASE + "/stats?since=2000-01-01T00:00:00Z").get("sinceTotal").asLong()).isEqualTo(3);
        mvc.perform(withKey(get(BASE + "/stats?since=yesterday"))).andExpect(status().isBadRequest());
    }

    @Test
    void exportIsGoldenSetJsonl() throws Exception {
        save("Young Anna", "NAME", "Anna");
        mvc.perform(withKey(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Fedaykin", "verdict", "NOT_A_NAME", "note", "Dune warrior caste")))));
        save("Peylak", "SKIPPED", null);

        String body = mvc.perform(withKey(get(BASE + "/export.jsonl")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn().getResponse().getContentAsString();
        String[] lines = body.split("\n");
        assertThat(lines).containsExactly(
                "{\"input\":\"Young Anna\",\"expected\":\"Anna\",\"source\":\"label\"}",
                "{\"input\":\"Fedaykin\",\"expected\":null,\"source\":\"label\",\"note\":\"Dune warrior caste\"}");

        String withSkipped = mvc.perform(withKey(get(BASE + "/export.jsonl?includeSkipped=true&set=label")))
                .andReturn().getResponse().getContentAsString();
        assertThat(withSkipped.split("\n")).hasSize(3);
        assertThat(withSkipped).contains("{\"input\":\"Peylak\",\"set\":\"label\",\"expected\":null,\"source\":\"label\",\"skipped\":true}");
    }

    @Test
    void lookupHookReturnsDecisionsOnly() throws Exception {
        save("Young Anna", "NAME", "Anna");
        save("Fedaykin", "NOT_A_NAME", null);
        save("Peylak", "SKIPPED", null);

        assertThat(labelService.lookup("Young Anna")).contains(new CharacterNameLabelService.LabelResult(Verdict.NAME, "Anna"));
        assertThat(labelService.lookup(" Fedaykin ")).hasValueSatisfying(r -> assertThat(r.isName()).isFalse());
        assertThat(labelService.lookup("Peylak")).isEmpty();
        assertThat(labelService.lookup("Nobody")).isEmpty();
        assertThat(labelService.lookupAll(List.of("Young Anna", "Peylak", "Nobody"))).containsOnlyKeys("Young Anna");
        assertThat(labelService.decisiveLabels()).containsOnlyKeys("Young Anna", "Fedaykin");
    }

    // ------------------------------------------------------------------ apply

    @Test
    void applyIsADryRunUnlessAskedAndDoesNotUndoOnDelete() throws Exception {
        character("Apply Young Anna", "Young", "Anna", NameType.STANDARD);
        character("Apply Fedaykin", "Apply", "Fedaykin", NameType.STANDARD);
        character("Apply Officer Daniels", null, "Daniels", NameType.TITLE_SURNAME);
        save("Apply Young Anna", "NAME", "Anna");
        save("Apply Fedaykin", "NOT_A_NAME", null);
        save("Apply Officer Daniels", "NOT_A_NAME", null);
        save("Apply Nobody", "NAME", "Nobody");
        save("Apply Skipped", "SKIPPED", null);

        mvc.perform(withKey(post(BASE + "/apply")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.labelsConsidered").value(4))
                .andExpect(jsonPath("$.labelsWithoutCharacters").value(1))
                .andExpect(jsonPath("$.charactersMatched").value(3))
                .andExpect(jsonPath("$.charactersChanged").value(2))
                .andExpect(jsonPath("$.charactersVerifiedOnly").value(1))
                .andExpect(jsonPath("$.samples[?(@.fullName == 'Apply Young Anna')].after.firstName").value("Anna"));
        assertThat(characters.findByFullName("Apply Young Anna").orElseThrow().getFirstName()).isEqualTo("Young");
        assertThat(labels.findByFullName("Apply Young Anna").orElseThrow().getAppliedAt()).isNull();

        mvc.perform(withKey(post(BASE + "/apply").param("dryRun", "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.charactersChanged").value(2));

        ScreenCharacter anna = characters.findByFullName("Apply Young Anna").orElseThrow();
        assertThat(anna.getFirstName()).isEqualTo("Anna");
        assertThat(anna.getLastName()).isNull();
        assertThat(anna.getNameType()).isEqualTo(NameType.STANDARD);
        assertThat(anna.isManuallyVerified()).isTrue();
        assertThat(anna.getReviewStatus()).isEqualTo(ScreenCharacter.ReviewStatus.APPROVED);

        ScreenCharacter fedaykin = characters.findByFullName("Apply Fedaykin").orElseThrow();
        assertThat(fedaykin.getFirstName()).isNull();
        assertThat(fedaykin.getLastName()).isNull();
        assertThat(fedaykin.getNameType()).isEqualTo(NameType.ROLE_DESCRIPTION);
        assertThat(fedaykin.getReviewStatus()).isEqualTo(ScreenCharacter.ReviewStatus.NO_FIRST_NAME);

        ScreenCharacter daniels = characters.findByFullName("Apply Officer Daniels").orElseThrow();
        assertThat(daniels.getLastName()).isEqualTo("Daniels");
        assertThat(daniels.getNameType()).isEqualTo(NameType.TITLE_SURNAME);
        assertThat(daniels.isManuallyVerified()).isTrue();

        assertThat(labels.findByFullName("Apply Young Anna").orElseThrow().getAppliedAt()).isNotNull();
        assertThat(getJson(BASE + "/stats").get("unapplied").asLong()).isZero();

        // Idempotent.
        mvc.perform(withKey(post(BASE + "/apply").param("dryRun", "false")))
                .andExpect(jsonPath("$.charactersChanged").value(0))
                .andExpect(jsonPath("$.charactersVerifiedOnly").value(0));

        // Changing a label marks it unapplied; apply can target a single label.
        save("Apply Young Anna", "NAME", "Annie");
        assertThat(getJson(BASE + "/stats").get("unapplied").asLong()).isEqualTo(1);
        mvc.perform(withKey(post(BASE + "/apply").param("dryRun", "false").param("fullName", "Apply Young Anna")))
                .andExpect(jsonPath("$.labelsConsidered").value(1))
                .andExpect(jsonPath("$.charactersChanged").value(1));
        assertThat(characters.findByFullName("Apply Young Anna").orElseThrow().getFirstName()).isEqualTo("Annie");

        // Deleting the label leaves the applied values in place.
        mvc.perform(withKey(delete(BASE).param("fullName", "Apply Young Anna"))).andExpect(status().isOk());
        assertThat(characters.findByFullName("Apply Young Anna").orElseThrow().getFirstName()).isEqualTo("Annie");
    }

    @Test
    void reviewEndpointToleratesNullEnums() throws Exception {
        ScreenCharacter c = character("Review Null Type", "Review", null, NameType.STANDARD);
        jdbc.update("UPDATE characters SET name_type = NULL WHERE id = ?", c.getId());
        mvc.perform(withKey(get("/api/v1/admin/character-names/review").param("limit", "500"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions save(String fullName, String verdict, String firstName) throws Exception {
        return mvc.perform(withKey(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(fullName, verdict, firstName)))));
    }

    private static Map<String, Object> body(String fullName, String verdict, String firstName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("verdict", verdict);
        body.put("firstName", firstName);
        return body;
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header(AdminApiKeyFilter.HEADER, KEY);
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(withKey(get(url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(i -> names.add(i.get("fullName").asText()));
        return names;
    }

    private Movie movie(String title) {
        Long id = jdbc.queryForObject("SELECT id FROM movies WHERE title = ?", Long.class, title);
        return movies.findById(id).orElseThrow();
    }

    private Person person(String first, String last) {
        Long id = jdbc.queryForObject("SELECT id FROM people WHERE first_name = ? AND last_name = ?", Long.class, first, last);
        return people.findById(id).orElseThrow();
    }

    private ScreenCharacter character(String fullName, String first, String last, NameType type) {
        return characters.findByFullName(fullName).orElseGet(() -> characters.save(ScreenCharacter.builder()
                .fullName(fullName).firstName(first).lastName(last).nameType(type).build()));
    }

    private void credit(Person person, Movie movie, ScreenCharacter character, int order) {
        credits.save(Credit.builder().person(person).movie(movie).character(character)
                .roleType(Credit.RoleType.CAST).department("Acting").job("Actor").order(order).build());
    }
}

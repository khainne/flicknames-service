package com.flicknames.service.service;

import com.flicknames.service.entity.DiminutiveUsage.Usage;
import com.flicknames.service.service.DiminutiveUsageModel.Pair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Families end to end on their own in-memory database, with the classifier mocked. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:name_family_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NameFamilyServiceTests {

    @Autowired
    private GivenNameCatalog catalog;

    @Autowired
    private NameFamilyService families;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private DiminutiveUsageModel model;

    @Test
    @SuppressWarnings("unchecked")
    void classifiesShortFormsBuildsFamiliesAndCountsUnsharedMembersOnce() {
        person("Nicholas", 900_001);
        person("Nick", 900_002);
        person("Nick", 900_003);
        person("Liam", 900_004);
        person("Kit", 900_005);
        long william = research("William");
        relation(william, "Liam", "DIMINUTIVE");
        relation(william, "Bill", "DIMINUTIVE");
        long nicholas = research("Nicholas");
        relation(nicholas, "Nick", "DIMINUTIVE");
        long christopher = research("Christopher");
        relation(christopher, "Kit", "DIMINUTIVE");
        long katherine = research("Katherine");
        relation(katherine, "Kit", "DIMINUTIVE");
        catalog.rebuild();

        when(model.configured()).thenReturn(true);
        when(model.classify(anyList())).thenAnswer(inv -> {
            Map<Long, Usage> usages = new HashMap<>();
            for (Pair p : (List<Pair>) inv.getArgument(0)) {
                usages.put(p.id(), p.shortForm().equals("Liam") ? Usage.INDEPENDENT : Usage.NICKNAME);
            }
            return new DiminutiveUsageModel.Result(usages, 100, 20, null);
        });
        NameFamilyService.ClassifyResult classified = families.classifyShortForms(100);
        assertThat(classified.independent()).isEqualTo(1);
        assertThat(families.classifyShortForms(100).pairs()).isZero();   // nothing left to ask

        NameFamilyService.RebuildResult rebuilt = families.rebuild();
        assertThat(rebuilt.independentLeftOut()).isEqualTo(1);
        assertThat(members("William")).containsExactly("William", "Bill");   // no Liam
        assertThat(families.familiesOf("Liam")).isEmpty();
        Map<String, Object> nick = families.familiesOf("Nick").get(0);
        assertThat(nick.get("label")).isEqualTo("Nicholas");
        assertThat(((Number) nick.get("people")).longValue()).isGreaterThanOrEqualTo(3);
        // Kit is in both families, counted in neither
        assertThat(families.familiesOf("Kit")).hasSize(2).allMatch(f -> Boolean.TRUE.equals(f.get("shared")));
        Long christophers = jdbc.queryForObject("SELECT COUNT(*) FROM people WHERE first_name = 'Christopher'", Long.class);
        assertThat(families.familiesOf("Christopher").get(0).get("people")).isEqualTo(christophers);   // Kit's person left out

        // An admin decision overrides the model at the next rebuild
        families.decideUsage("Liam", "William", Usage.NICKNAME);
        families.rebuild();
        assertThat(members("William")).contains("Liam");
    }

    private List<String> members(String spelling) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) families.familiesOf(spelling).get(0).get("memberList")) {
            out.add((String) m.get("spelling"));
        }
        return out;
    }

    private void person(String firstName, long tmdbId) {
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, gender, tmdb_person_id, created_at, updated_at) "
            + "VALUES (?, 'Test', ?, 'Unknown', ?, NOW(), NOW())", firstName, firstName + " Test", tmdbId);
    }

    private long research(String name) {
        jdbc.update("INSERT INTO name_research (name, gender_classification, confidence_score, status, created_at, updated_at) "
            + "VALUES (?, 'MASCULINE', 90, 'APPROVED', NOW(), NOW())", name);
        return jdbc.queryForObject("SELECT id FROM name_research WHERE name = ?", Long.class, name);
    }

    private void relation(long researchId, String related, String type) {
        jdbc.update("INSERT INTO name_relationship (name_research_id, related_name, relationship_type) VALUES (?, ?, ?)",
            researchId, related, type);
    }
}

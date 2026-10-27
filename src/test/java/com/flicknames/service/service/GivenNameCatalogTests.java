package com.flicknames.service.service;

import com.flicknames.service.entity.GivenNameLink.Status;
import com.flicknames.service.entity.GivenNameLink.Type;
import com.flicknames.service.research.service.NameResearchService;
import com.flicknames.service.service.GivenNameCatalog.LinkView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses its own in-memory database, so the catalog and research rows stay out of other test classes. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:given_name_catalog_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GivenNameCatalogTests {

    @Autowired
    private GivenNameCatalog catalog;

    @Autowired
    private NameResearchService researchService;

    @Autowired
    private CompoundGivenNames compoundGivenNames;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void buildsNamesAndLinksKeepsAdminDecisionsAndFollowsResearchApproval() {
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO people (first_name, last_name, full_name, gender, tmdb_person_id, created_at, updated_at) "
                + "VALUES ('Nick', 'Doe', 'Nick Doe', 'Male', ?, NOW(), NOW())", 800_000 + i);
        }
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, gender, tmdb_person_id, created_at, updated_at) "
            + "VALUES ('José', 'Ruiz', 'José Ruiz', 'Male', 800100, NOW(), NOW()), "
            + "('Jose', 'Ruiz', 'Jose Ruiz', 'Unknown', 800101, NOW(), NOW())");
        long nicholas = research("Nicholas", "APPROVED");
        relation(nicholas, "Nick", "DIMINUTIVE");
        relation(nicholas, "Klaus", "COGNATE");
        long nick = research("Nick", "PENDING");
        relation(nick, "Nicholas", "VARIANT");

        catalog.rebuild();
        assertThat(jdbc.queryForObject("SELECT people_count FROM given_names WHERE spelling = 'Nick'", Integer.class))
            .isGreaterThanOrEqualTo(3);
        assertThat(catalog.name("Jose").orElseThrow().sameFoldedSpellings()).contains("José");
        assertThat(links("Nick")).extracting(LinkView::from, LinkView::to, LinkView::type, LinkView::status, LinkView::confidence)
            .contains(org.assertj.core.groups.Tuple.tuple("Nick", "Nicholas", "DIMINUTIVE_OF", "ACCEPTED", 70));
        // Mary Kate (seeded) links to both of its words
        assertThat(links("Mary Kate")).extracting(LinkView::to).contains("Mary", "Kate");

        // Approving Nick's card adds its agreement (more confidence) without a manual rebuild
        researchService.approveResearch(nick);
        assertThat(links("Nick")).filteredOn(l -> l.source().equals("RESEARCH"))
            .extracting(LinkView::confidence).containsOnly(85);

        // An admin rejection survives rebuilds
        catalog.decide("Klaus", "Nicholas", Type.COGNATE, Status.REJECTED);
        catalog.rebuild();
        assertThat(links("Klaus")).extracting(LinkView::source, LinkView::status)
            .contains(org.assertj.core.groups.Tuple.tuple("ADMIN", "REJECTED"), org.assertj.core.groups.Tuple.tuple("RESEARCH", "ACCEPTED"));

        // Disabling a compound removes its links
        compoundGivenNames.disable("Mary Kate");
        assertThat(links("Mary Kate")).isEmpty();

        // The schema refuses a symmetric link stored the wrong way round
        Long low = jdbc.queryForObject("SELECT MIN(id) FROM given_names", Long.class);
        Long high = jdbc.queryForObject("SELECT MAX(id) FROM given_names", Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO given_name_links (from_id, to_id, type, source, status, confidence) "
            + "VALUES (?, ?, 'COGNATE', 'ADMIN', 'ACCEPTED', 100)", high, low)).isInstanceOf(Exception.class);
    }

    private List<LinkView> links(String spelling) {
        return catalog.name(spelling).map(GivenNameCatalog.NameView::links).orElse(List.of());
    }

    private long research(String name, String status) {
        jdbc.update("INSERT INTO name_research (name, gender_classification, confidence_score, status, created_at, updated_at) "
            + "VALUES (?, 'MASCULINE', 90, ?, NOW(), NOW())", name, status);
        return jdbc.queryForObject("SELECT id FROM name_research WHERE name = ?", Long.class, name);
    }

    private void relation(long researchId, String related, String type) {
        jdbc.update("INSERT INTO name_relationship (name_research_id, related_name, relationship_type) VALUES (?, ?, ?)",
            researchId, related, type);
    }
}

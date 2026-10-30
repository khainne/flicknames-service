package com.flicknames.service.service;

import com.flicknames.service.dto.NameStatsDTO;
import com.flicknames.service.dto.TrendingNameDTO;
import com.flicknames.service.service.NameFamilyService.FamilyPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One page per name across accent spellings, and the family view, on their own in-memory database. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:accent_merged_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccentMergedNameTests {

    @Autowired
    private NameService nameService;

    @Autowired
    private CharacterNameService characterNameService;

    @Autowired
    private UnifiedNameService unifiedNameService;

    @Autowired
    private GivenNameCatalog catalog;

    @Autowired
    private NameFamilyService families;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void accentSpellingsShareOnePageAndAFamilyView() {
        person("Jose", 700_001);
        person("Jose", 700_002);
        person("José", 700_003);
        jdbc.update("INSERT INTO characters (full_name, first_name, last_name, name_type, manually_verified, created_at, updated_at) "
            + "VALUES ('José Arcadio', 'José', 'Arcadio', 'STANDARD', FALSE, NOW(), NOW())");
        jdbc.update("INSERT INTO name_research (name, gender_classification, confidence_score, status, created_at, updated_at) "
            + "VALUES ('Jose', 'MASCULINE', 90, 'APPROVED', NOW(), NOW())");
        Long research = jdbc.queryForObject("SELECT id FROM name_research WHERE name = 'Jose'", Long.class);
        jdbc.update("INSERT INTO name_relationship (name_research_id, related_name, relationship_type) VALUES (?, 'Pepe', 'DIMINUTIVE')", research);
        person("Pepe", 700_004);
        catalog.rebuild();
        families.rebuild();

        for (String asked : List.of("José", "jose", "JOSÈ")) {
            NameStatsDTO stats = nameService.getNameStats(asked);
            assertThat(stats.getName()).isEqualTo("Jose");   // the most common spelling
            assertThat(stats.getPeopleCount()).isEqualTo(3);
        }
        assertThat(nameService.getPeopleByFirstName("josé")).hasSize(3);
        assertThat(characterNameService.getCharactersByFirstName("Jose")).extracting("fullName").contains("José Arcadio");

        FamilyPage page = families.page("JOSÉ");
        assertThat(page.name()).isEqualTo("Jose");
        assertThat(page.spellings()).containsExactly("Jose", "José");
        assertThat(page.families()).hasSize(1);
        NameFamilyService.FamilyView family = page.families().get(0);
        assertThat(family.label()).isEqualTo("Jose");
        assertThat(family.role()).isEqualTo("HEAD");
        // José and Jose are one member entry; Pepe is a short form
        assertThat(family.members()).extracting(NameFamilyService.MemberView::name).containsExactly("Jose", "Pepe");
        assertThat(family.members().get(0).people()).isEqualTo(3);
        assertThat(family.people()).isEqualTo(4);
        assertThat(families.page("Zelda").families()).isEmpty();
    }

    @Test
    void trendingListsMergeAccentSpellingsUnderTheLargerOne() {
        List<TrendingNameDTO> merged = unifiedNameService.mergeAndAggregate(
            List.of(trending("Jose", 300), trending("José", 100), trending("Anna", 50)),
            List.of(trending("josé", 20)), 10);
        assertThat(merged).extracting(TrendingNameDTO::getName).containsExactly("Jose", "Anna");
        assertThat(merged.get(0).getTotalRevenue()).isEqualByComparingTo("420");
        assertThat(merged.get(0).getPeopleCount()).isEqualTo(3);
    }

    private static TrendingNameDTO trending(String name, long revenue) {
        return TrendingNameDTO.builder().name(name).totalRevenue(BigDecimal.valueOf(revenue)).movieCount(1).peopleCount(1).build();
    }

    private void person(String firstName, long tmdbId) {
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, gender, tmdb_person_id, created_at, updated_at) "
            + "VALUES (?, 'Test', ?, 'Male', ?, NOW(), NOW())", firstName, firstName + " Test " + tmdbId, tmdbId);
    }
}

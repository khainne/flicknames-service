package com.flicknames.service.service;

import com.flicknames.service.entity.CharacterNameLabel.Verdict;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.service.CharacterNameLabelService.LabelRequest;
import com.flicknames.service.service.CharacterNameReparseService.*;
import com.flicknames.service.util.charactername.CharacterNameParserV2;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dry run, report, apply and revert against its own database (it rewrites character names). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reparse_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CharacterNameReparseServiceTests {

    @Autowired
    private CharacterNameReparseService service;

    @Autowired
    private CharacterNameLabelService labelService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CharacterNameResolver resolver;

    @Test
    void dryRunStagesOnlyThenApplyWritesAndRevertRestores() {
        jdbc.update("INSERT INTO movies (title, tmdb_movie_id, revenue, release_date, created_at, updated_at) "
            + "VALUES ('Reparse Movie', 990001, 500000000, DATE '1999-06-01', NOW(), NOW())");
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, tmdb_person_id, created_at, updated_at) "
            + "VALUES ('Some', 'Actor', 'Some Actor', 990001, NOW(), NOW())");
        character("Young Anna", "Young", "Anna", "STANDARD", false);
        character("Doctor", "Doctor", null, "SINGLE_NAME", false);
        character("Verified Thing", "Verified", null, "SINGLE_NAME", true);
        character("Fedaykin", "Fedaykin", null, "SINGLE_NAME", false);
        character("Pat Morita Junior", "Pat", "Morita Junior", "STANDARD", false);
        credit("Young Anna");
        credit("Doctor");
        labelService.save(new LabelRequest("Fedaykin", Verdict.NOT_A_NAME, null, null, "test"));

        DryRunResult dryRun = service.dryRun();
        assertThat(dryRun.skippedVerified()).isEqualTo(1);
        assertThat(dryRun.fromLabels()).isEqualTo(1);
        assertThat(dryRun.parserVersion()).isEqualTo(CharacterNameParserV2.VERSION);
        // Nothing written to characters yet
        assertThat(firstName("Young Anna")).isEqualTo("Young");
        assertThat(staged("Young Anna")).containsEntry("NEW_FIRST_NAME", "Anna").containsEntry("OLD_FIRST_NAME", "Young");
        assertThat(staged("Doctor").get("NEW_FIRST_NAME")).isNull();
        assertThat(staged("Fedaykin")).containsEntry("SOURCE", "LABEL");
        assertThat(count("SELECT COUNT(*) FROM character_reparse_rows WHERE full_name = 'Verified Thing'")).isZero();

        Report report = service.report(100, null, null);
        assertThat(report.summary().nameToNull()).isGreaterThanOrEqualTo(2); // Doctor, Fedaykin
        YearRanking y1999 = report.years().stream().filter(y -> Integer.valueOf(1999).equals(y.year())).findFirst().orElseThrow();
        assertThat(y1999.before()).extracting(RankedName::name).contains("Young", "Doctor");
        assertThat(y1999.after()).extracting(RankedName::name).contains("Anna").doesNotContain("Young", "Doctor");
        assertThat(y1999.entered()).extracting(RankedName::name).contains("Anna");
        assertThat(y1999.left()).extracting(RankedName::name).contains("Young", "Doctor");
        assertThat(report.removedNames()).extracting(Transition::from).contains("Doctor");
        assertThat(service.rows(Change.NAME_TO_NULL, null, null, null, null, 100))
            .extracting(StagedRow::fullName).contains("Doctor", "Fedaykin").doesNotContain("Young Anna");
        assertThat(service.rows(null, null, null, "Young", null, 100)).extracting(StagedRow::newFirstName)
            .containsExactly("Anna");

        // Edited after the dry run: apply must leave it alone
        jdbc.update("UPDATE characters SET first_name = 'Edited' WHERE full_name = 'Pat Morita Junior'");

        ApplyResult applied = service.apply();
        assertThat(applied.skippedStale()).isEqualTo(1);
        assertThat(firstName("Young Anna")).isEqualTo("Anna");
        assertThat(jdbc.queryForObject("SELECT last_name FROM characters WHERE full_name = 'Young Anna'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT parser_version FROM characters WHERE full_name = 'Young Anna'", String.class))
            .isEqualTo(CharacterNameParserV2.VERSION);
        assertThat(jdbc.queryForObject("SELECT parse_confidence FROM characters WHERE full_name = 'Young Anna'", Double.class))
            .isNotNull();
        assertThat(firstName("Doctor")).isNull();
        assertThat(firstName("Pat Morita Junior")).isEqualTo("Edited");
        assertThat(firstName("Verified Thing")).isEqualTo("Verified");
        // The label went through the label apply, which also marks the row verified
        assertThat(firstName("Fedaykin")).isNull();
        assertThat(jdbc.queryForObject("SELECT manually_verified FROM characters WHERE full_name = 'Fedaykin'", Boolean.class)).isTrue();
        // Applying twice changes nothing more
        assertThat(service.apply().applied()).isZero();

        // A new dry run would replace the backup
        assertThatThrownBy(() -> service.startDryRun(false)).isInstanceOf(ReparseConflictException.class);

        // Characters collected after the dry run: a catch-up run stages just those and keeps the backup
        character("Young Hagrid", "Young", "Hagrid", "STANDARD", false);
        DryRunResult catchUp = service.dryRun(true);
        assertThat(catchUp.staged()).isEqualTo(1);
        assertThat(staged("Young Anna").get("APPLIED_AT")).isNotNull();
        ApplyResult caughtUp = service.apply();
        assertThat(caughtUp.applied()).isEqualTo(1);
        assertThat(firstName("Young Hagrid")).isEqualTo("Hagrid");

        // A fixed parser over an applied run: pretend an earlier version wrote "Wrong" for Young Anna
        jdbc.update("UPDATE characters SET first_name = 'Wrong' WHERE full_name = 'Young Anna'");
        jdbc.update("UPDATE character_reparse_rows SET new_first_name = 'Wrong', applied_first_name = 'Wrong' "
            + "WHERE full_name = 'Young Anna'");
        // ...and someone edited Doctor after the apply: a refresh must leave it alone
        jdbc.update("UPDATE characters SET first_name = 'Edited Doctor' WHERE full_name = 'Doctor'");
        DryRunResult refreshed = service.dryRun(true, true);
        assertThat(refreshed.replanned()).isEqualTo(1);
        assertThat(staged("Young Anna")).containsEntry("NEW_FIRST_NAME", "Anna").containsEntry("OLD_FIRST_NAME", "Young");
        assertThat(staged("Young Anna").get("APPLIED_AT")).isNull();
        assertThat(service.apply().applied()).isEqualTo(1);
        assertThat(firstName("Young Anna")).isEqualTo("Anna");
        jdbc.update("UPDATE characters SET first_name = NULL WHERE full_name = 'Doctor'");

        RevertResult reverted = service.revert();
        assertThat(reverted.reverted()).isEqualTo(applied.applied() + caughtUp.applied());
        assertThat(firstName("Young Hagrid")).isEqualTo("Young");
        assertThat(firstName("Young Anna")).isEqualTo("Young");
        assertThat(jdbc.queryForObject("SELECT last_name FROM characters WHERE full_name = 'Young Anna'", String.class)).isEqualTo("Anna");
        assertThat(jdbc.queryForObject("SELECT parser_version FROM characters WHERE full_name = 'Young Anna'", String.class)).isNull();
        assertThat(firstName("Doctor")).isEqualTo("Doctor");
        assertThat(count("SELECT COUNT(*) FROM character_reparse_rows WHERE applied_at IS NOT NULL")).isZero();
    }

    @Test
    void resolverUsesALabelFirstThenParserV2() {
        labelService.save(new LabelRequest("Groot (voice)", Verdict.NOT_A_NAME, null, null, "test"));
        ScreenCharacter labelled = ScreenCharacter.builder().fullName("Groot (voice)").build();
        resolver.resolve(labelled);
        assertThat(labelled.getFirstName()).isNull();
        assertThat(labelled.isManuallyVerified()).isTrue();
        assertThat(labelled.getParserVersion()).isEqualTo(CharacterNameReparseService.LABEL_VERSION);

        ScreenCharacter parsed = ScreenCharacter.builder().fullName("Young Forrest Gump").build();
        resolver.resolve(parsed);
        assertThat(parsed.getFirstName()).isEqualTo("Forrest");
        assertThat(parsed.getLastName()).isEqualTo("Gump");
        assertThat(parsed.getParserVersion()).isEqualTo(CharacterNameParserV2.VERSION);
        assertThat(parsed.getParseConfidence()).isNotNull();
        assertThat(parsed.isManuallyVerified()).isFalse();
    }

    @Test
    void personFirstNameCountsUseTheFirstWordOfFullNames() {
        jdbc.update("INSERT INTO people (first_name, last_name, full_name, tmdb_person_id, created_at, updated_at) "
            + "VALUES ('Mietek', 'Kowalski', 'Mietek Kowalski', 990101, NOW(), NOW()), "
            + "('Mietek', 'Nowak', 'Mietek Nowak', 990102, NOW(), NOW()), "
            + "('J.', 'Smith', 'J. Smith', 990103, NOW(), NOW())");
        assertThat(service.personFirstNameCounts(2)).containsEntry("mietek", 2).doesNotContainKey("j.");
        assertThat(service.personFirstNameCounts(3)).doesNotContainKey("mietek");
    }

    private void character(String fullName, String first, String last, String type, boolean verified) {
        jdbc.update("INSERT INTO characters (full_name, first_name, last_name, name_type, manually_verified, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())", fullName, first, last, type, verified);
    }

    private void credit(String characterName) {
        jdbc.update("INSERT INTO credits (movie_id, person_id, character_id, role_type, department, job, created_at) "
            + "SELECT m.id, p.id, c.id, 'CAST', 'Acting', 'Actor', NOW() FROM movies m, people p, characters c "
            + "WHERE m.tmdb_movie_id = 990001 AND p.tmdb_person_id = 990001 AND c.full_name = ?", characterName);
    }

    private String firstName(String fullName) {
        return jdbc.queryForObject("SELECT first_name FROM characters WHERE full_name = ?", String.class, fullName);
    }

    private Map<String, Object> staged(String fullName) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM character_reparse_rows WHERE full_name = ?", fullName);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}

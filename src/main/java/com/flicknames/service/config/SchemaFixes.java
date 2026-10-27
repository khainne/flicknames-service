package com.flicknames.service.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema changes that {@code ddl-auto=update} does not make. Hibernate writes an enum's allowed values
 * into a check constraint only when it creates the table, so a value added later (the re-parse source
 * MODEL) is rejected by tables created earlier. Constraints Hibernate does not derive from the entities (the
 * given_name_links checks and foreign keys) are added here too. Each fix is idempotent and runs at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaFixes implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        replaceCheck("character_reparse_rows", "character_reparse_rows_source_check",
            "source IN ('PARSER', 'LABEL', 'MODEL')");
        replaceCheck("name_relationship", "name_relationship_relationship_type_check",
            "relationship_type IN ('VARIANT', 'DIMINUTIVE', 'FEMININE_FORM', 'MASCULINE_FORM', 'COGNATE', 'SHORT_FORM_OF')");
        replaceCheck("given_name_links", "given_name_links_distinct_check", "from_id <> to_id");
        replaceCheck("given_name_links", "given_name_links_symmetric_order_check",
            "type NOT IN ('SPELLING_VARIANT', 'COGNATE') OR from_id < to_id");
        replaceConstraint("given_name_links", "fk_given_name_link_from", "FOREIGN KEY (from_id) REFERENCES given_names (id)");
        replaceConstraint("given_name_links", "fk_given_name_link_to", "FOREIGN KEY (to_id) REFERENCES given_names (id)");
    }

    private void replaceCheck(String table, String constraint, String condition) {
        replaceConstraint(table, constraint, "CHECK (" + condition + ")");
    }

    private void replaceConstraint(String table, String constraint, String definition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + definition);
        } catch (Exception e) {
            log.warn("Could not update constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}

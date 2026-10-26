package com.flicknames.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaFixesTest {

    @Test
    void replacesAReparseSourceCheckCreatedBeforeModelExisted() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:schema_fixes_test;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE character_reparse_rows (character_id BIGINT PRIMARY KEY, source VARCHAR(10), "
            + "CONSTRAINT character_reparse_rows_source_check CHECK (source IN ('PARSER', 'LABEL')))");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO character_reparse_rows VALUES (1, 'MODEL')")).isInstanceOf(Exception.class);

        SchemaFixes fixes = new SchemaFixes(jdbc);
        fixes.run(null);
        fixes.run(null);   // idempotent

        assertThatCode(() -> jdbc.update("INSERT INTO character_reparse_rows VALUES (1, 'MODEL')")).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO character_reparse_rows VALUES (2, 'BOGUS')")).isInstanceOf(Exception.class);
    }
}

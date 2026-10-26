package com.flicknames.service.service;

import com.flicknames.service.entity.CompoundGivenName.Source;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses its own in-memory database, so adding and disabling names does not reach other test classes. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:compound_given_names_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompoundGivenNamesTests {

    @Autowired
    private CompoundGivenNames names;

    @Test
    void seedsAtStartupAndMatchesIgnoringCaseAndAccents() {
        assertThat(names.all()).hasSize(CompoundGivenNames.seedNames().size());
        assertThat(names.isCompound("Jose", "LUIS")).isTrue();
        assertThat(names.isCompound("María", "José")).isTrue();
        assertThat(names.isCompound("David", "Anthony")).isFalse();
    }

    @Test
    void disabledNamesStayDisabledUntilAnAdminAddsThemAgain() {
        assertThat(names.add(List.of("Kyu Ri", "Not A Pair", "Solo"), Source.ADMIN)).hasSize(1);
        assertThat(names.isCompound("Kyu", "Ri")).isTrue();

        assertThat(names.disable("kyu  ri")).isTrue();
        assertThat(names.isCompound("Kyu", "Ri")).isFalse();
        assertThat(names.add(List.of("Kyu Ri"), Source.SEED)).isEmpty();   // what a restart's seed does
        assertThat(names.isCompound("Kyu", "Ri")).isFalse();

        assertThat(names.add(List.of("Kyu Ri"), Source.ADMIN)).hasSize(1);
        assertThat(names.isCompound("Kyu", "Ri")).isTrue();
        assertThat(names.disable("Nobody Here")).isFalse();
    }
}

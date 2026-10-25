package com.flicknames.service.util.charactername;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Runs every case in {@code parser/golden.jsonl}. Accuracy floors live in {@link CharacterNameParserV2MetricsTest}. */
class CharacterNameParserV2GoldenTest {

    private static final CharacterNameParserV2 PARSER = new CharacterNameParserV2();
    private static final Set<String> KNOWN_FAILURES = loadKnownFailures();

    static Stream<GoldenCase> scoredCases() {
        return GoldenCase.scored().stream();
    }

    static Stream<GoldenCase> ambiguousCases() {
        return GoldenCase.load().stream().filter(GoldenCase::ambiguous);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scoredCases")
    void goldenCase(GoldenCase c) {
        CharacterNameParseResult r = PARSER.parse(c.input());
        assumeFalse(KNOWN_FAILURES.contains(c.input()),
            () -> "known failure: got " + r.firstName() + ", expected " + c.expected());
        assertThat(r.firstName()).as("%s (%s, %s)", c.input(), r.nameType(), r.reasons()).isEqualTo(c.expected());
        assertWellFormed(r);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ambiguousCases")
    void ambiguousCaseStillParses(GoldenCase c) {
        assertWellFormed(PARSER.parse(c.input()));
    }

    @Test
    void knownFailuresAreStillFailures() {
        List<GoldenCase> scored = GoldenCase.scored();
        assertThat(scored.stream().map(GoldenCase::input).collect(Collectors.toSet())).containsAll(KNOWN_FAILURES);
        List<String> nowPassing = scored.stream()
            .filter(c -> KNOWN_FAILURES.contains(c.input()))
            .filter(c -> Objects.equals(PARSER.parse(c.input()).firstName(), c.expected()))
            .map(GoldenCase::input)
            .toList();
        assertThat(nowPassing).as("now passing; remove from golden-known-failures.txt").isEmpty();
    }

    @Test
    void goldenSetHasExpectedShape() {
        List<GoldenCase> all = GoldenCase.load();
        assertThat(all).hasSize(750);
        assertThat(all.stream().filter(c -> c.set().equals("tune"))).hasSize(300);
        assertThat(all.stream().filter(c -> c.set().equals("holdout"))).hasSize(150);
        assertThat(all.stream().filter(c -> c.set().equals("fresh"))).hasSize(150);
        assertThat(all.stream().filter(c -> c.set().equals("fresh2"))).hasSize(150);
        assertThat(all.stream().map(GoldenCase::input).distinct()).hasSize(750);
    }

    private static void assertWellFormed(CharacterNameParseResult r) {
        assertThat(r.confidence()).isBetween(0.0, 1.0);
        assertThat(r.parserVersion()).isEqualTo(CharacterNameParserV2.VERSION);
        assertThat(r.reasons()).isNotEmpty();
        assertThat(r.nameType()).isNotNull();
        if (r.firstName() != null) {
            assertThat(r.firstName()).isNotBlank().doesNotContain(" ", "(", ")", "/", "\"");
            assertThat(Character.isUpperCase(r.firstName().codePointAt(0))).as(r.firstName()).isTrue();
        }
    }

    private static Set<String> loadKnownFailures() {
        try (InputStream in = CharacterNameParserV2GoldenTest.class.getClassLoader()
                .getResourceAsStream("parser/golden-known-failures.txt")) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

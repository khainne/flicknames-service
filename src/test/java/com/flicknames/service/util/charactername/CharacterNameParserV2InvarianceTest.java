package com.flicknames.service.util.charactername;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** Properties that must hold for every input in the golden set (ambiguous cases included). */
class CharacterNameParserV2InvarianceTest {

    private static final CharacterNameParserV2 PARSER = new CharacterNameParserV2();
    private static final List<String> INPUTS = GoldenCase.load().stream().map(GoldenCase::input).toList();

    private static String first(String s) {
        return PARSER.parse(s).firstName();
    }

    private static List<String> violations(UnaryOperator<String> variant) {
        List<String> bad = new ArrayList<>();
        for (String in : INPUTS) {
            String a = first(in);
            String b = first(variant.apply(in));
            if (!Objects.equals(a, b)) {
                bad.add(in + " -> " + a + " but variant -> " + b);
            }
        }
        return bad;
    }

    @Test
    void voiceAnnotationDoesNotChangeTheName() {
        assertThat(violations(s -> s + " (voice)")).isEmpty();
        assertThat(violations(s -> s + " (uncredited)")).isEmpty();
        assertThat(violations(s -> s + " [archive footage]")).isEmpty();
    }

    @Test
    void spacingAroundSlashDoesNotMatter() {
        assertThat(violations(s -> s.replaceAll("\\s*/\\s*", "/"))).isEmpty();
        assertThat(violations(s -> s.replaceAll("\\s*/\\s*", " / "))).isEmpty();
        assertThat(violations(s -> s.replaceAll("\\s*/\\s*", "  /   "))).isEmpty();
    }

    @Test
    void extraWhitespaceAndCurlyQuotesDoNotMatter() {
        assertThat(violations(s -> "  " + s.replace(" ", "   ") + "\t")).isEmpty();
        assertThat(violations(s -> s.replace('\'', '’'))).isEmpty();
    }

    @Test
    void parsingIsDeterministic() {
        for (String in : INPUTS) {
            assertThat(PARSER.parse(in)).isEqualTo(PARSER.parse(in));
            assertThat(new CharacterNameParserV2().parse(in)).isEqualTo(PARSER.parse(in));
        }
    }

    @Test
    void reparsingTheCleanedNameIsIdempotent() {
        List<String> bad = new ArrayList<>();
        for (String in : INPUTS) {
            CharacterNameParseResult r = PARSER.parse(in);
            if (!Objects.equals(first(r.cleanedName()), r.firstName())) {
                bad.add(in + " -> " + r.firstName() + ", cleaned '" + r.cleanedName() + "' -> " + first(r.cleanedName()));
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void anExtractedNameParsesToItself() {
        List<String> bad = new ArrayList<>();
        CharacterNameLexicon lexicon = CharacterNameLexicon.loadDefault();
        for (String in : INPUTS) {
            String name = first(in);
            // A bare English word ("Flip" from "Flip Zimmerman") is deliberately rejected on its own.
            if (name != null && !lexicon.isEnglishWord(CharacterNameLexicon.fold(name)) && !name.equals(first(name))) {
                bad.add(in + " -> " + name + " -> " + first(name));
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void upperAndLowerCaseInputGiveTheSameName() {
        List<String> bad = new ArrayList<>();
        for (String in : INPUTS) {
            String upper = first(in.toUpperCase(Locale.ROOT));
            String lower = first(in.toLowerCase(Locale.ROOT));
            if (upper == null ? lower != null : !upper.equalsIgnoreCase(lower)) {
                bad.add(in + ": upper -> " + upper + ", lower -> " + lower);
            }
        }
        System.out.println("[parser-invariance] upper/lower disagreements: " + bad);
        assertThat(bad).isEmpty();
    }

    @Test
    void namesSurviveAllCapsAndAllLowercase() {
        List<String> bad = new ArrayList<>();
        for (GoldenCase c : GoldenCase.scored()) {
            if (c.expected() == null || !c.expected().equals(first(c.input()))) {
                continue;
            }
            for (String variant : List.of(c.input().toUpperCase(Locale.ROOT), c.input().toLowerCase(Locale.ROOT))) {
                String got = first(variant);
                if (got == null || !got.equalsIgnoreCase(c.expected())) {
                    bad.add(variant + " -> " + got + " (expected " + c.expected() + ")");
                }
            }
        }
        System.out.println("[parser-invariance] casing losses: " + bad.size() + " " + bad);
        assertThat(bad).isEmpty();
    }
}

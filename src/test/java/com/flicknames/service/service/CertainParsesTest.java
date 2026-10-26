package com.flicknames.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.util.charactername.CharacterNameParseResult;
import com.flicknames.service.util.charactername.CharacterNameParserV2;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The certainty rules decide which parses skip the model pass, so they must not be wrong on any labelled
 * credit: the golden set and the blind production samples. A rule that starts missing fails this test.
 */
class CertainParsesTest {

    private static final CharacterNameParserV2 PARSER = new CharacterNameParserV2();

    @Test
    void everyLabelledCreditARuleCoversIsParsedCorrectly() throws IOException {
        Map<String, String> labels = new LinkedHashMap<>();
        read("/parser/golden.jsonl", n -> labels.put(n.get("input").asText(), text(n.get("expected"))));
        read("/parser/production-audit.jsonl", n -> labels.put(n.get("input").asText(), text(n.get("expected"))));

        Map<String, Integer> covered = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        labels.forEach((input, expected) -> {
            CharacterNameParseResult result = PARSER.parse(input);
            String rule = CertainParses.rule(result);
            if (rule == null) {
                return;
            }
            covered.merge(rule, 1, Integer::sum);
            if (!Objects.equals(fold(result.firstName()), fold(expected))) {
                wrong.add(rule + ": " + input + " -> " + result.firstName() + " (expected " + expected + ")");
            }
        });
        System.out.println("[certain-parses] covered " + covered + " of " + labels.size());
        assertThat(wrong).isEmpty();
        assertThat(covered.values().stream().mapToInt(Integer::intValue).sum()).isGreaterThan(250);
    }

    @Test
    void examples() {
        assertThat(CertainParses.rule(PARSER.parse("Self - Host"))).isEqualTo("self credit");
        assertThat(CertainParses.rule(PARSER.parse("Cop #2"))).isEqualTo("numbered extra");
        assertThat(CertainParses.rule(PARSER.parse("Collègue Michel 2"))).isNull();   // a name could hide here
        assertThat(CertainParses.rule(PARSER.parse("Teri Montana"))).isEqualTo("first last");
        assertThat(CertainParses.rule(PARSER.parse("Diamond Skinner"))).isNull();     // "diamond" is a word
        assertThat(CertainParses.rule(PARSER.parse("Killer Kane"))).isNull();
        assertThat(CertainParses.rule(PARSER.parse("Ojciec"))).isNull();
    }

    private static String fold(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static void read(String resource, java.util.function.Consumer<JsonNode> sink) throws IOException {
        ObjectMapper json = new ObjectMapper();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(CertainParsesTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    sink.accept(json.readTree(line));
                }
            }
        }
    }
}

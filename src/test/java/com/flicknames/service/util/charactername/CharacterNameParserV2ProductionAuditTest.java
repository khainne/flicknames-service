package com.flicknames.service.util.charactername;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accuracy on production-audit.jsonl: character strings sampled from the production dry run of
 * 2.0.0 (600 uniformly at random over all 362k characters, ~300 more among parses below 0.7
 * confidence), labelled blind to any parser output. "stored" is the first name the database held
 * (the v1 parser). Unlike the golden set, individual cases may fail; the floors guard the totals.
 * The parser was tuned against this sample in 2.1.0, so treat its numbers as training accuracy.
 */
class CharacterNameParserV2ProductionAuditTest {

    private static final CharacterNameParserV2 PARSER = new CharacterNameParserV2();

    record Case(String input, String sample, String expected, boolean unsure, String stored) {}

    @Test
    void accuracyOnProductionSample() throws IOException {
        List<Case> cases = load();
        assertThat(cases).hasSizeGreaterThan(800);
        double uniform = accuracy(cases, "uniform", false);
        double uniformStored = accuracy(cases, "uniform", true);
        double lowConfidence = accuracy(cases, "lowconf", false);
        System.out.printf("[parser-audit] uniform: v2 %.1f%% (stored v1 %.1f%%), low-confidence sample: v2 %.1f%%%n",
            uniform, uniformStored, lowConfidence);
        assertThat(uniform).isGreaterThanOrEqualTo(87.0);
        assertThat(lowConfidence).isGreaterThanOrEqualTo(68.0);
    }

    private static double accuracy(List<Case> cases, String sample, boolean stored) {
        List<Case> subset = cases.stream().filter(c -> c.sample().equals(sample)).toList();
        long right = subset.stream()
            .filter(c -> Objects.equals(fold(stored ? c.stored() : PARSER.parse(c.input()).firstName()), fold(c.expected())))
            .count();
        return 100.0 * right / subset.size();
    }

    private static String fold(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static List<Case> load() throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<Case> cases = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(CharacterNameParserV2ProductionAuditTest.class
                    .getResourceAsStream("/parser/production-audit.jsonl")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = json.readTree(line);
                cases.add(new Case(n.get("input").asText(), n.get("sample").asText(), text(n, "expected"),
                    n.get("unsure").asBoolean(), text(n, "stored")));
            }
        }
        return cases;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

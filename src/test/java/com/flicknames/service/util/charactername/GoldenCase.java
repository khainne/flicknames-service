package com.flicknames.service.util.charactername;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One hand-labelled character credit from {@code src/test/resources/parser/golden.jsonl}.
 *
 * <p>{@code set} is "tune" (gold300: the cases v2 was tuned on), "holdout" (holdout150, not tuned
 * on), "fresh" (150 more rows of the same production sample, labelled blind) or "fresh2" (one random
 * cast credit from each of 150 random production movies, labelled blind, never tuned on). See
 * {@link CharacterNameParserV2MetricsTest}. Ambiguous cases stay in the file but are not scored.
 */
record GoldenCase(String input, String expected, String set, boolean ambiguous, String note) {

    static final String RESOURCE = "parser/golden.jsonl";

    static List<GoldenCase> load() {
        ObjectMapper mapper = new ObjectMapper();
        List<GoldenCase> cases = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(GoldenCase.class.getClassLoader().getResourceAsStream(RESOURCE));
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = mapper.readTree(line);
                cases.add(new GoldenCase(
                    n.get("input").asText(),
                    n.hasNonNull("expected") ? n.get("expected").asText() : null,
                    n.get("set").asText(),
                    n.path("ambiguous").asBoolean(false),
                    n.hasNonNull("note") ? n.get("note").asText() : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }

    static List<GoldenCase> scored() {
        return load().stream().filter(c -> !c.ambiguous()).toList();
    }

    @Override
    public String toString() {
        return "[" + set + "] " + input + " -> " + expected;
    }
}

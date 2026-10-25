package com.flicknames.service.util.charactername;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.entity.NamePattern.PatternType;
import com.flicknames.service.util.CharacterNameParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline dry run: parses a sample of production character strings with v1 (default seed patterns)
 * and v2, and writes a markdown report plus a per-row CSV. Reads and writes local files only.
 *
 * <pre>
 * mvn -q test -Dtest=CharacterNameParserDryRun \
 *     -Dparser.dryrun.input=sample_all.json -Dparser.dryrun.out=out-dir [-Dparser.dryrun.patterns=patterns.json]
 * </pre>
 * The input is a JSON array of {"full_name": ..., "first_name": ...} rows; the optional patterns
 * file (an export of the production name_patterns table, grouped by type) adds a v1-with-production-
 * patterns comparison. Not part of the normal test run (the class name does not match the surefire
 * includes) and skipped unless {@code parser.dryrun.input} is set.
 */
class CharacterNameParserDryRun {

    static final double LOW_CONFIDENCE = 0.7;
    private static final int EXAMPLES = 50;

    enum Change { UNCHANGED, NAME_TO_NULL, NULL_TO_NAME, NAME_TO_DIFFERENT_NAME }

    record Row(String fullName, String stored, String v1, String v1Prod, CharacterNameParseResult v2) {
        Change change() {
            return classify(v1, v2.firstName());
        }
    }

    @Test
    void dryRun() throws IOException {
        String input = System.getProperty("parser.dryrun.input");
        assumeTrue(input != null && !input.isBlank(), "set -Dparser.dryrun.input to run the dry run");
        String out = System.getProperty("parser.dryrun.out", ".");
        run(Path.of(input), Path.of(out), optionalPath(System.getProperty("parser.dryrun.patterns")));
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: CharacterNameParserDryRun <sample.json> <out-dir> [patterns.json]");
            System.exit(2);
        }
        run(Path.of(args[0]), Path.of(args[1]), args.length > 2 ? Path.of(args[2]) : null);
    }

    static void run(Path input, Path outDir, Path patternsFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CharacterNameParser v1 = V1Baseline.withDefaultPatterns();
        CharacterNameParser v1Prod = patternsFile == null ? null : V1Baseline.withPatterns(loadPatterns(mapper, patternsFile));
        CharacterNameParserV2 v2 = new CharacterNameParserV2();

        List<Row> rows = new ArrayList<>();
        for (JsonNode n : mapper.readTree(input.toFile())) {
            String name = n.path("full_name").asText(null);
            if (name == null) {
                continue;
            }
            rows.add(new Row(name, n.hasNonNull("first_name") ? n.get("first_name").asText() : null,
                v1.parse(name).getFirstName(),
                v1Prod == null ? null : v1Prod.parse(name).getFirstName(),
                v2.parse(name)));
        }
        Files.createDirectories(outDir);
        writeCsv(rows, outDir.resolve("parser-v2-dryrun.csv"));
        writeMarkdown(rows, input, v1Prod != null, outDir.resolve("parser-v2-dryrun.md"));
        System.out.println("[parser-dryrun] wrote " + outDir.resolve("parser-v2-dryrun.md") + " and .csv for " + rows.size() + " rows");
    }

    static Change classify(String before, String after) {
        if (Objects.equals(before, after)) {
            return Change.UNCHANGED;
        }
        if (after == null) {
            return Change.NAME_TO_NULL;
        }
        return before == null ? Change.NULL_TO_NAME : Change.NAME_TO_DIFFERENT_NAME;
    }

    // ------------------------------------------------------------------ report

    private static void writeMarkdown(List<Row> rows, Path input, boolean withProd, Path file) throws IOException {
        int total = rows.size();
        Map<Change, List<Row>> byChange = new EnumMap<>(Change.class);
        for (Change c : Change.values()) {
            byChange.put(c, new ArrayList<>());
        }
        rows.forEach(r -> byChange.get(r.change()).add(r));
        int changed = total - byChange.get(Change.UNCHANGED).size();
        long caseOnly = byChange.get(Change.NAME_TO_DIFFERENT_NAME).stream()
            .filter(r -> r.v1().equalsIgnoreCase(r.v2().firstName())).count();

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.printf("# Character-name parser v2 dry run%n%n");
            w.printf("- Input: `%s` (%d rows, %d distinct strings)%n", input.getFileName(), total,
                rows.stream().map(Row::fullName).distinct().count());
            w.printf("- Baseline: v1 `CharacterNameParser` with the default `NamePatternSeeder` patterns%n");
            w.printf("- Candidate: `CharacterNameParserV2` %s%n", CharacterNameParserV2.VERSION);
            w.printf("- Nothing was written anywhere except this report and the CSV next to it.%n%n");

            w.printf("## Summary%n%n| | rows | share |%n|---|---:|---:|%n");
            row(w, "first name changes (v1 -> v2)", changed, total);
            row(w, "name -> null", byChange.get(Change.NAME_TO_NULL).size(), total);
            row(w, "null -> name", byChange.get(Change.NULL_TO_NAME).size(), total);
            row(w, "name -> different name", byChange.get(Change.NAME_TO_DIFFERENT_NAME).size(), total);
            row(w, "  of which casing only", (int) caseOnly, total);
            row(w, "unchanged", byChange.get(Change.UNCHANGED).size(), total);
            row(w, "v1 returns a name", (int) rows.stream().filter(r -> r.v1() != null).count(), total);
            row(w, "v2 returns a name", (int) rows.stream().filter(r -> r.v2().hasFirstName()).count(), total);
            long low = rows.stream().filter(r -> r.v2().confidence() < LOW_CONFIDENCE).count();
            row(w, "v2 confidence < " + LOW_CONFIDENCE + " (LLM-stage candidates)", (int) low, total);
            w.println();

            if (withProd) {
                w.printf("### Against v1 with the production pattern table%n%n| | rows | share |%n|---|---:|---:|%n");
                Map<Change, Long> prod = rows.stream()
                    .collect(Collectors.groupingBy(r -> classify(r.v1Prod(), r.v2().firstName()), () -> new EnumMap<>(Change.class), Collectors.counting()));
                for (Change c : Change.values()) {
                    row(w, c.name().toLowerCase(Locale.ROOT).replace('_', ' '), prod.getOrDefault(c, 0L).intValue(), total);
                }
                w.println();
            }

            w.printf("## v2 confidence distribution%n%n| confidence | rows | with name | null |%n|---|---:|---:|---:|%n");
            TreeMap<String, int[]> buckets = new TreeMap<>(Comparator.reverseOrder());
            for (Row r : rows) {
                double c = r.v2().confidence();
                String b = c >= 0.9 ? "0.90-1.00" : c >= 0.8 ? "0.80-0.89" : c >= 0.7 ? "0.70-0.79"
                    : c >= 0.6 ? "0.60-0.69" : c >= 0.5 ? "0.50-0.59" : "0.00-0.49";
                int[] counts = buckets.computeIfAbsent(b, k -> new int[2]);
                counts[r.v2().hasFirstName() ? 0 : 1]++;
            }
            buckets.forEach((b, c) -> w.printf("| %s | %d | %d | %d |%n", b, c[0] + c[1], c[0], c[1]));
            w.println();

            w.printf("## v2 name types%n%n| type | rows |%n|---|---:|%n");
            counts(rows, r -> r.v2().nameType().name()).forEach((k, v) -> w.printf("| %s | %d |%n", k, v));
            w.println();
            w.printf("## v2 reason codes (rows carrying each)%n%n| reason | rows |%n|---|---:|%n");
            Map<String, Integer> reasons = new TreeMap<>();
            rows.forEach(r -> r.v2().reasons().stream().distinct().forEach(x -> reasons.merge(x.name(), 1, Integer::sum)));
            reasons.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> w.printf("| %s | %d |%n", e.getKey(), e.getValue()));
            w.println();

            for (Change c : List.of(Change.NAME_TO_NULL, Change.NULL_TO_NAME, Change.NAME_TO_DIFFERENT_NAME)) {
                List<Row> list = byChange.get(c);
                w.printf("## %s: %d rows (first %d distinct)%n%n", c.name().toLowerCase(Locale.ROOT).replace('_', ' '), list.size(), EXAMPLES);
                examples(w, list);
            }

            List<Row> lowRows = rows.stream().filter(r -> r.v2().confidence() < LOW_CONFIDENCE).toList();
            w.printf("## Low confidence (< %s): %d rows, %.1f%% (first %d distinct)%n%n", LOW_CONFIDENCE, lowRows.size(),
                100.0 * lowRows.size() / total, EXAMPLES);
            w.printf("These are the candidates for the later LLM stage.%n%n| threshold | rows below | share |%n|---|---:|---:|%n");
            for (double t : new double[] {0.5, 0.6, 0.7, 0.8}) {
                row(w, "< " + t, (int) rows.stream().filter(r -> r.v2().confidence() < t).count(), total);
            }
            w.println();
            Map<String, Integer> lowReasons = counts(lowRows, r -> lastReason(r.v2()));
            w.printf("Most common deciding reason: %s%n%n", lowReasons.entrySet().stream().limit(8)
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
            examples(w, lowRows);
        }
    }

    private static void examples(PrintWriter w, List<Row> list) {
        w.printf("| character | v1 | v2 | v2 type | conf | reasons |%n|---|---|---|---|---:|---|%n");
        list.stream().map(Row::fullName).distinct().sorted().limit(EXAMPLES).forEach(name -> {
            Row r = list.stream().filter(x -> x.fullName().equals(name)).findFirst().orElseThrow();
            w.printf("| %s | %s | %s | %s | %.2f | %s |%n", md(r.fullName()), md(r.v1()), md(r.v2().firstName()),
                r.v2().nameType(), r.v2().confidence(),
                r.v2().reasons().stream().map(Enum::name).collect(Collectors.joining(" ")));
        });
        w.println();
    }

    private static String lastReason(CharacterNameParseResult r) {
        return r.reasons().isEmpty() ? "-" : r.reasons().get(r.reasons().size() - 1).name();
    }

    private static Map<String, Integer> counts(List<Row> rows, Function<Row, String> key) {
        Map<String, Integer> m = new LinkedHashMap<>();
        rows.stream().collect(Collectors.groupingBy(key, Collectors.counting())).entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> m.put(e.getKey(), e.getValue().intValue()));
        return m;
    }

    private static void row(PrintWriter w, String label, int n, int total) {
        w.printf("| %s | %d | %.1f%% |%n", label, n, 100.0 * n / total);
    }

    private static String md(String s) {
        return s == null ? "_null_" : s.replace("|", "\\|");
    }

    private static void writeCsv(List<Row> rows, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("full_name,stored_first_name,v1_default,v1_production_patterns,v2_first_name,change,v2_name_type,v2_confidence,v2_reasons,v2_nickname");
            for (Row r : rows) {
                CharacterNameParseResult v = r.v2();
                w.println(String.join(",", csv(r.fullName()), csv(r.stored()), csv(r.v1()), csv(r.v1Prod()),
                    csv(v.firstName()), r.change().name(), v.nameType().name(), String.format(Locale.ROOT, "%.2f", v.confidence()),
                    csv(v.reasons().stream().map(Enum::name).collect(Collectors.joining(" "))), csv(v.nickname())));
            }
        }
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static Map<PatternType, List<String>> loadPatterns(ObjectMapper mapper, Path file) throws IOException {
        Map<PatternType, List<String>> patterns = new EnumMap<>(PatternType.class);
        JsonNode root = mapper.readTree(file.toFile());
        for (PatternType t : PatternType.values()) {
            List<String> values = new ArrayList<>();
            root.path(t.name()).forEach(p -> values.add(p.path("patternValue").asText()));
            patterns.put(t, values);
        }
        return patterns;
    }

    private static Path optionalPath(String s) {
        return s == null || s.isBlank() ? null : Path.of(s);
    }
}

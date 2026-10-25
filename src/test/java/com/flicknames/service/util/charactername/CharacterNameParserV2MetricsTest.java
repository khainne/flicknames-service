package com.flicknames.service.util.charactername;

import com.flicknames.service.util.CharacterNameParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores v2 (and, for comparison, v1 with its default seed patterns) on the golden set and fails
 * if v2 drops below the floors. Floors sit just under what v2 achieved when it was committed, so
 * a regression fails the build; raise them when the parser improves.
 *
 * <p>Sets: "tune" (gold300) is what v2 was tuned on. "holdout" (holdout150) was not tuned on, but
 * its inputs and labels were visible while the lexicons were written, so treat it as optimistic.
 * "fresh" (150 more rows of the same sample) was labelled blind before it was first scored
 * (first-look v2: accuracy 91.9%, precision 84.9%, recall 98.4%); a few generic fixes were made
 * after looking at its errors. "fresh2" (one random cast credit from each of 150 random production
 * movies) was labelled blind and has never been tuned on: it is the cleanest estimate.
 */
class CharacterNameParserV2MetricsTest {

    private static final CharacterNameParserV2 V2 = new CharacterNameParserV2();
    private static List<GoldenCase> tune;
    private static List<GoldenCase> holdout;
    private static List<GoldenCase> fresh;
    private static List<GoldenCase> fresh2;

    @BeforeAll
    static void load() {
        tune = GoldenCase.scored().stream().filter(c -> c.set().equals("tune")).toList();
        holdout = GoldenCase.scored().stream().filter(c -> c.set().equals("holdout")).toList();
        fresh = GoldenCase.scored().stream().filter(c -> c.set().equals("fresh")).toList();
        fresh2 = GoldenCase.scored().stream().filter(c -> c.set().equals("fresh2")).toList();
    }

    private static String v2(String s) {
        return V2.parse(s).firstName();
    }

    @Test
    void tuningSetMeetsFloors() {
        ParserMetrics m = ParserMetrics.score("v2 tune", tune, CharacterNameParserV2MetricsTest::v2);
        print(m, true);
        assertThat(m.accuracy()).isGreaterThanOrEqualTo(Floors.TUNE_ACCURACY);
        assertThat(m.precision()).isGreaterThanOrEqualTo(Floors.TUNE_PRECISION);
        assertThat(m.recall()).isGreaterThanOrEqualTo(Floors.TUNE_RECALL);
    }

    @Test
    void holdoutSetMeetsFloors() {
        ParserMetrics m = ParserMetrics.score("v2 holdout", holdout, CharacterNameParserV2MetricsTest::v2);
        print(m, true);
        assertThat(m.accuracy()).isGreaterThanOrEqualTo(Floors.HOLDOUT_ACCURACY);
        assertThat(m.precision()).isGreaterThanOrEqualTo(Floors.HOLDOUT_PRECISION);
        assertThat(m.recall()).isGreaterThanOrEqualTo(Floors.HOLDOUT_RECALL);
    }

    @Test
    void freshSetMeetsFloors() {
        ParserMetrics m = ParserMetrics.score("v2 fresh", fresh, CharacterNameParserV2MetricsTest::v2);
        print(m, true);
        assertThat(m.accuracy()).isGreaterThanOrEqualTo(Floors.FRESH_ACCURACY);
        assertThat(m.precision()).isGreaterThanOrEqualTo(Floors.FRESH_PRECISION);
        assertThat(m.recall()).isGreaterThanOrEqualTo(Floors.FRESH_RECALL);
    }

    @Test
    void fresh2SetMeetsFloors() {
        ParserMetrics m = ParserMetrics.score("v2 fresh2", fresh2, CharacterNameParserV2MetricsTest::v2);
        print(m, true);
        assertThat(m.accuracy()).isGreaterThanOrEqualTo(Floors.FRESH2_ACCURACY);
        assertThat(m.precision()).isGreaterThanOrEqualTo(Floors.FRESH2_PRECISION);
        assertThat(m.recall()).isGreaterThanOrEqualTo(Floors.FRESH2_RECALL);
    }

    @Test
    void overallMeetsFloorsAndBeatsV1() {
        List<GoldenCase> all = GoldenCase.scored();
        ParserMetrics m2 = ParserMetrics.score("v2 all", all, CharacterNameParserV2MetricsTest::v2);
        CharacterNameParser v1 = V1Baseline.withDefaultPatterns();
        ParserMetrics m1 = ParserMetrics.score("v1(default) all", all, s -> v1.parse(s).getFirstName());
        print(ParserMetrics.score("v1(default) tune", tune, s -> v1.parse(s).getFirstName()), false);
        print(ParserMetrics.score("v1(default) holdout", holdout, s -> v1.parse(s).getFirstName()), false);
        print(ParserMetrics.score("v1(default) fresh", fresh, s -> v1.parse(s).getFirstName()), false);
        print(ParserMetrics.score("v1(default) fresh2", fresh2, s -> v1.parse(s).getFirstName()), false);
        print(m1, false);
        print(m2, false);
        assertThat(m2.accuracy()).isGreaterThanOrEqualTo(Floors.ALL_ACCURACY).isGreaterThan(m1.accuracy());
        assertThat(m2.precision()).isGreaterThanOrEqualTo(Floors.ALL_PRECISION).isGreaterThan(m1.precision());
        assertThat(m2.recall()).isGreaterThanOrEqualTo(Floors.ALL_RECALL);
    }

    @Test
    void confidenceIsCalibratedAroundTheLlmThreshold() {
        double threshold = CharacterNameParserDryRun.LOW_CONFIDENCE;
        int[] high = new int[2];
        int[] low = new int[2];
        for (GoldenCase c : GoldenCase.scored()) {
            CharacterNameParseResult r = V2.parse(c.input());
            int[] bucket = r.confidence() >= threshold ? high : low;
            bucket[0]++;
            if (java.util.Objects.equals(r.firstName(), c.expected())) {
                bucket[1]++;
            }
        }
        System.out.printf("[parser-metrics] confidence >= %.1f: %d cases, accuracy %.1f%%; below: %d cases, accuracy %.1f%%%n",
            threshold, high[0], 100.0 * high[1] / high[0], low[0], 100.0 * low[1] / Math.max(1, low[0]));
        assertThat((double) high[1] / high[0]).isGreaterThan((double) low[1] / Math.max(1, low[0]));
    }

    private static void print(ParserMetrics m, boolean errors) {
        System.out.println("[parser-metrics] " + m.summary());
        if (errors && Boolean.getBoolean("parser.metrics.verbose")) {
            m.errors().forEach(e -> System.out.println("[parser-metrics]    " + e));
        }
    }

    /** Set just below the numbers v2 2.0.0 achieved; see the class comment. */
    private static final class Floors {
        static final double TUNE_ACCURACY = 0.98;
        static final double TUNE_PRECISION = 0.97;
        static final double TUNE_RECALL = 0.98;
        static final double HOLDOUT_ACCURACY = 0.97;
        static final double HOLDOUT_PRECISION = 0.96;
        static final double HOLDOUT_RECALL = 0.98;
        static final double FRESH_ACCURACY = 0.94;
        static final double FRESH_PRECISION = 0.89;
        static final double FRESH_RECALL = 0.98;
        static final double FRESH2_ACCURACY = 0.95;
        static final double FRESH2_PRECISION = 0.95;
        static final double FRESH2_RECALL = 0.96;
        static final double ALL_ACCURACY = 0.97;
        static final double ALL_PRECISION = 0.95;
        static final double ALL_RECALL = 0.98;
    }
}

package com.flicknames.service.util.charactername;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Accuracy / precision / recall of a parser over labelled cases.
 *
 * <ul>
 *   <li>accuracy: output equals the label (a correct {@code null} counts)</li>
 *   <li>precision: share of non-null outputs that are the labelled name</li>
 *   <li>recall: share of labelled names that are returned exactly</li>
 * </ul>
 */
record ParserMetrics(String label, int n, int truePositives, int trueNegatives, int falsePositives,
                     int wrongName, int falseNegatives, List<String> errors) {

    static ParserMetrics score(String label, List<GoldenCase> cases, Function<String, String> parser) {
        int tp = 0, tn = 0, fp = 0, wrong = 0, fn = 0;
        List<String> errors = new ArrayList<>();
        for (GoldenCase c : cases) {
            String got = parser.apply(c.input());
            if (Objects.equals(got, c.expected())) {
                if (got == null) {
                    tn++;
                } else {
                    tp++;
                }
            } else if (c.expected() == null) {
                fp++;
                errors.add("FP  " + c.input() + " -> " + got);
            } else if (got == null) {
                fn++;
                errors.add("FN  " + c.input() + " -> null (expected " + c.expected() + ")");
            } else {
                wrong++;
                errors.add("WT  " + c.input() + " -> " + got + " (expected " + c.expected() + ")");
            }
        }
        return new ParserMetrics(label, cases.size(), tp, tn, fp, wrong, fn, errors);
    }

    double accuracy() {
        return (truePositives + trueNegatives) / (double) n;
    }

    double precision() {
        int predicted = truePositives + falsePositives + wrongName;
        return predicted == 0 ? 0 : truePositives / (double) predicted;
    }

    double recall() {
        int named = truePositives + wrongName + falseNegatives;
        return named == 0 ? 0 : truePositives / (double) named;
    }

    String summary() {
        return String.format("%-22s n=%3d  accuracy=%5.1f%%  precision=%5.1f%%  recall=%5.1f%%  (TP=%d TN=%d FP=%d wrongName=%d FN=%d)",
            label, n, 100 * accuracy(), 100 * precision(), 100 * recall(),
            truePositives, trueNegatives, falsePositives, wrongName, falseNegatives);
    }
}

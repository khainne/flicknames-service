package com.flicknames.service.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the credits from one window of releases into ranked index rows. A name's weighted revenue
 * is, for each film, the film's revenue x the name's best credit weight on that film, so twelve
 * crew members called Michael on one film count once. The index is that as a share (in percent)
 * of all names' weighted revenue.
 */
public final class NameIndexCalculator {

    /** Letters, with apostrophes or hyphens inside: "Zoë", "D'Arcy", "Mary-Kate". Not initials. */
    private static final Pattern VALID_NAME = Pattern.compile("\\p{L}[\\p{L}'\\u2019-]*\\p{L}");

    public record CreditRow(String firstName, long movieId, double revenue, double weight) {
    }

    public record IndexRow(String name, String nameKey, double weightedRevenue, double indexValue,
                           int rank, int movieCount) {
    }

    private NameIndexCalculator() {
    }

    public static List<IndexRow> compute(List<CreditRow> credits, int keepTop) {
        // nameKey -> movieId -> best (revenue x weight); also remember a display spelling.
        Map<String, Map<Long, Double>> perName = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (CreditRow row : credits) {
            String name = row.firstName() == null ? "" : row.firstName().trim();
            if (row.revenue() <= 0 || row.weight() <= 0 || !VALID_NAME.matcher(name).matches()) {
                continue;
            }
            String key = name.toLowerCase();
            display.merge(key, name, NameIndexCalculator::preferredSpelling);
            perName.computeIfAbsent(key, k -> new HashMap<>())
                .merge(row.movieId(), row.revenue() * row.weight(), Math::max);
        }

        double total = 0;
        List<IndexRow> rows = new ArrayList<>(perName.size());
        Map<String, Double> weighted = new HashMap<>();
        for (Map.Entry<String, Map<Long, Double>> e : perName.entrySet()) {
            double sum = e.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            weighted.put(e.getKey(), sum);
            total += sum;
        }
        if (total <= 0) {
            return rows;
        }

        List<String> keys = new ArrayList<>(weighted.keySet());
        keys.sort(Comparator.<String>comparingDouble(weighted::get).reversed().thenComparing(k -> k));
        int limit = Math.min(keepTop, keys.size());
        for (int i = 0; i < limit; i++) {
            String key = keys.get(i);
            double w = weighted.get(key);
            rows.add(new IndexRow(display.get(key), key, w, 100.0 * w / total, i + 1, perName.get(key).size()));
        }
        return rows;
    }

    /** Prefer the capitalised spelling ("Michael" over "MICHAEL" or "michael"). */
    private static String preferredSpelling(String a, String b) {
        return score(b) > score(a) ? b : a;
    }

    private static int score(String s) {
        boolean firstUpper = Character.isUpperCase(s.codePointAt(0));
        boolean restLower = s.substring(Character.charCount(s.codePointAt(0))).chars().noneMatch(Character::isUpperCase);
        return (firstUpper ? 2 : 0) + (restLower ? 1 : 0);
    }
}

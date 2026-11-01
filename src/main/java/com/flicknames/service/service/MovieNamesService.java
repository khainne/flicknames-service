package com.flicknames.service.service;

import com.flicknames.service.util.charactername.CharacterNameLexicon;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The first names in one film's credits and how unusual they are: each name's rarity tier (from all-time
 * US births), whether the film is its first appearance on screen in the catalogue, and film-level scores
 * compared with other films released the same year.
 *
 * <p>Every person credited (cast and crew alike) counts once per film, however many jobs they had.
 * Characters are reported separately and don't enter the scores. Spellings that differ only by accents
 * or case (José, Jose) are one name throughout.
 */
@Service
public class MovieNamesService {

    /** Rarity of a first name by all-time US births (SSA, both sexes). */
    public enum Tier {
        /** Among the 100 most-given names of all time */
        EVERYDAY,
        /** Among the 1,000 most-given */
        COMMON,
        /** Outside the top 1,000 but given to at least 1,000 babies */
        UNCOMMON,
        /** Fewer than 1,000 babies ever */
        RARE,
        /** No US baby on record */
        NEVER_REGISTERED
    }

    static final int TOP_EVERYDAY = 100;
    static final int TOP_COMMON = 1000;
    static final long UNCOMMON_MIN_BIRTHS = 1000;
    /** "Top 20" names for the share of credits held by the very commonest names */
    static final int TOP_HEAVY = 20;
    /** Films with fewer people credited are left out of the year's benchmark */
    static final int BENCHMARK_MIN_PEOPLE = 20;
    private static final int IN_CHUNK = 500;
    private static final Duration CACHE_FOR = Duration.ofHours(12);

    private final JdbcTemplate jdbc;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();

    public MovieNamesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- public view

    public record MovieNames(long movieId, String title, LocalDate releaseDate, LocalDate catalogFrom,
                             Summary summary, Benchmark benchmark, List<NameEntry> names,
                             List<CharacterEntry> characters) {}

    /**
     * {@code variety} is distinct first names over people credited; {@code topHeavyShare} the share of people
     * whose name is one of the 20 most-given US names; {@code unusualShare} the share whose name is
     * {@link Tier#UNCOMMON} or rarer.
     */
    public record Summary(int people, int distinctNames, double variety, double topHeavyShare, double unusualShare,
                          Map<Tier, Integer> tiers, int debutNames, int neverRegisteredNames) {}

    /** How this film compares with others released the same year ({@code percentile}: share of films below it). */
    public record Benchmark(int year, int films, double averageVariety, double varietyPercentile,
                            double averageUnusualShare, double unusualPercentile) {}

    /**
     * One first name among the people credited. {@code firstSeen} is its earliest release date anywhere in
     * the catalogue (as a person or a character); {@code debut} is true when that's this film's release.
     */
    public record NameEntry(String name, int people, boolean cast, boolean crew, Tier tier, long usBirths,
                            Integer allTimeRank, int filmPeople, int filmCharacters, LocalDate firstSeen,
                            boolean debut) {}

    public record CharacterEntry(String character, String firstName, String actor, Tier tier, long usBirths,
                                 Integer allTimeRank, LocalDate firstSeen, boolean debut) {}

    /** Empty when the movie doesn't exist. */
    public Optional<MovieNames> movieNames(long movieId) {
        return cached("movie:" + movieId, () -> compute(movieId));
    }

    // ---------------------------------------------------------------- computation

    private record CreditRow(long personId, String personFirst, String roleType, String characterFirst,
                             String characterName, Long characterId, String personName) {}

    /** Name counts across the catalogue for one folded name, summed over its spellings. */
    private record Known(String folded, long births, int people, int characters) {}

    private Optional<MovieNames> compute(long movieId) {
        List<Map<String, Object>> movie = jdbc.queryForList(
            "SELECT title, release_date FROM movies WHERE id = ?", movieId);
        if (movie.isEmpty()) {
            return Optional.empty();
        }
        String title = (String) movie.get(0).get("title");
        LocalDate released = toDate(movie.get(0).get("release_date"));

        List<CreditRow> rows = jdbc.query("""
            SELECT p.id AS person_id, p.first_name AS person_first, p.full_name AS person_name, c.role_type,
                   ch.id AS character_id, ch.first_name AS character_first, ch.full_name AS character_name
            FROM credits c JOIN people p ON p.id = c.person_id
            LEFT JOIN characters ch ON ch.id = c.character_id
            WHERE c.movie_id = ?""",
            (rs, i) -> new CreditRow(rs.getLong("person_id"), rs.getString("person_first"), rs.getString("role_type"),
                rs.getString("character_first"), rs.getString("character_name"),
                (Long) rs.getObject("character_id"), rs.getString("person_name")), movieId);

        // folded name -> people with it (each person once), cast/crew flags, display spelling
        Map<String, Set<Long>> peopleByName = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();
        Set<String> castNames = new HashSet<>();
        Set<String> crewNames = new HashSet<>();
        Set<Long> people = new HashSet<>();
        Map<Long, CreditRow> characters = new LinkedHashMap<>();
        for (CreditRow r : rows) {
            String key = key(r.personFirst());
            if (key != null) {
                people.add(r.personId());
                peopleByName.computeIfAbsent(key, k -> new HashSet<>()).add(r.personId());
                display.putIfAbsent(key, r.personFirst().trim());
                ("CAST".equals(r.roleType()) ? castNames : crewNames).add(key);
            }
            if (r.characterId() != null && key(r.characterFirst()) != null) {
                characters.putIfAbsent(r.characterId(), r);
            }
        }

        Set<String> allKeys = new HashSet<>(peopleByName.keySet());
        // Spellings as written in this film, so names missing from given_names are still looked up
        Set<String> spellingsHere = new HashSet<>();
        for (CreditRow r : rows) {
            if (key(r.personFirst()) != null) {
                spellingsHere.add(r.personFirst().trim());
            }
        }
        characters.values().forEach(c -> {
            allKeys.add(key(c.characterFirst()));
            spellingsHere.add(c.characterFirst().trim());
        });
        Map<String, Known> known = known(allKeys);
        Map<String, LocalDate> firstSeen = firstSeen(allKeys, spellingsHere);
        Ranking ranking = ranking();

        List<NameEntry> names = new ArrayList<>();
        Map<Tier, Integer> tiers = new EnumMap<>(Tier.class);
        Arrays.stream(Tier.values()).forEach(t -> tiers.put(t, 0));
        int topHeavy = 0;
        int unusual = 0;
        int debuts = 0;
        int never = 0;
        for (Map.Entry<String, Set<Long>> e : peopleByName.entrySet()) {
            String k = e.getKey();
            int n = e.getValue().size();
            Known kn = known.getOrDefault(k, new Known(k, 0, 0, 0));
            Tier tier = ranking.tier(kn.births());
            Integer rank = ranking.rank(kn.births());
            LocalDate seen = firstSeen.get(k);
            boolean debut = released != null && seen != null && !seen.isBefore(released);
            names.add(new NameEntry(display.get(k), n, castNames.contains(k), crewNames.contains(k), tier,
                kn.births(), rank, kn.people(), kn.characters(), seen, debut));
            tiers.merge(tier, n, Integer::sum);
            if (rank != null && rank <= TOP_HEAVY) {
                topHeavy += n;
            }
            if (tier.ordinal() >= Tier.UNCOMMON.ordinal()) {
                unusual += n;
            }
            if (debut) {
                debuts++;
            }
            if (tier == Tier.NEVER_REGISTERED) {
                never++;
            }
        }
        // Most repeated first, then rarest
        names.sort(Comparator.comparingInt(NameEntry::people).reversed()
            .thenComparing(NameEntry::usBirths));

        List<CharacterEntry> characterEntries = new ArrayList<>();
        for (CreditRow c : characters.values()) {
            String k = key(c.characterFirst());
            Known kn = known.getOrDefault(k, new Known(k, 0, 0, 0));
            LocalDate seen = firstSeen.get(k);
            characterEntries.add(new CharacterEntry(c.characterName(), c.characterFirst().trim(), c.personName(),
                ranking.tier(kn.births()), kn.births(), ranking.rank(kn.births()), seen,
                released != null && seen != null && !seen.isBefore(released)));
        }

        int total = people.size();
        double variety = total == 0 ? 0 : (double) peopleByName.size() / total;
        double unusualShare = total == 0 ? 0 : (double) unusual / total;
        Summary summary = new Summary(total, peopleByName.size(), round(variety), round(total == 0 ? 0 : (double) topHeavy / total),
            round(unusualShare), tiers, debuts, never);
        Benchmark benchmark = released == null || total < BENCHMARK_MIN_PEOPLE ? null
            : benchmark(released.getYear(), variety, unusualShare);

        return Optional.of(new MovieNames(movieId, title, released, catalogFrom(), summary, benchmark, names,
            characterEntries));
    }

    /** Folded form used to group a first name, or null for blanks and initials ("J.", "A"). */
    static String key(String firstName) {
        if (firstName == null) {
            return null;
        }
        String f = CharacterNameLexicon.fold(firstName.trim());
        if (f.length() < 2 || f.endsWith(".") || f.chars().noneMatch(Character::isLetter)) {
            return null;
        }
        return f;
    }

    /** US births and catalogue counts for each folded name, over all its spellings. */
    private Map<String, Known> known(Collection<String> keys) {
        Map<String, long[]> sums = new HashMap<>();
        for (List<String> chunk : chunks(keys)) {
            jdbc.query("SELECT folded, ssa_male + ssa_female AS births, people_count, character_count "
                    + "FROM given_names WHERE folded IN (" + placeholders(chunk.size()) + ")",
                rs -> {
                    long[] s = sums.computeIfAbsent(rs.getString("folded"), k -> new long[3]);
                    s[0] += rs.getLong("births");
                    s[1] += rs.getLong("people_count");
                    s[2] += rs.getLong("character_count");
                }, chunk.toArray());
        }
        Map<String, Known> out = new HashMap<>();
        sums.forEach((k, s) -> out.put(k, new Known(k, s[0], (int) s[1], (int) s[2])));
        return out;
    }

    /** Earliest release date on which each folded name appears in the catalogue, as a person or a character. */
    private Map<String, LocalDate> firstSeen(Collection<String> keys, Collection<String> spellingsHere) {
        Set<String> spellings = new HashSet<>(spellingsHere);
        for (List<String> chunk : chunks(keys)) {
            spellings.addAll(jdbc.queryForList("SELECT spelling FROM given_names WHERE folded IN ("
                + placeholders(chunk.size()) + ")", String.class, chunk.toArray()));
        }
        Map<String, LocalDate> seen = new HashMap<>();
        for (List<String> s : chunks(spellings)) {
            String in = placeholders(s.size());
            for (String sql : List.of(
                "SELECT p.first_name AS n, MIN(m.release_date) AS d FROM people p "
                    + "JOIN credits c ON c.person_id = p.id JOIN movies m ON m.id = c.movie_id "
                    + "WHERE p.first_name IN (" + in + ") AND m.release_date IS NOT NULL GROUP BY p.first_name",
                "SELECT ch.first_name AS n, MIN(m.release_date) AS d FROM characters ch "
                    + "JOIN credits c ON c.character_id = ch.id JOIN movies m ON m.id = c.movie_id "
                    + "WHERE ch.first_name IN (" + in + ") AND m.release_date IS NOT NULL GROUP BY ch.first_name")) {
                jdbc.query(sql, rs -> {
                    String k = key(rs.getString("n"));
                    LocalDate d = toDate(rs.getObject("d"));
                    if (k != null && d != null) {
                        seen.merge(k, d, (a, b) -> a.isBefore(b) ? a : b);
                    }
                }, s.toArray());
            }
        }
        return seen;
    }

    private LocalDate catalogFrom() {
        return cached("catalogFrom", () -> toDate(jdbc.queryForObject(
            "SELECT MIN(release_date) FROM movies WHERE release_date IS NOT NULL", Object.class)));
    }

    // ---------------------------------------------------------------- tiers and ranks

    /** All-time US births per folded name, most-given first, for ranks and tier thresholds. */
    record Ranking(long[] desc, Map<String, Long> byName) {
        Tier tier(long births) {
            if (births <= 0) {
                return Tier.NEVER_REGISTERED;
            }
            Integer rank = rank(births);
            if (rank != null && rank <= TOP_EVERYDAY) {
                return Tier.EVERYDAY;
            }
            if (rank != null && rank <= TOP_COMMON) {
                return Tier.COMMON;
            }
            return births >= UNCOMMON_MIN_BIRTHS ? Tier.UNCOMMON : Tier.RARE;
        }

        /** 1 + names given to more babies; null for names never registered. */
        Integer rank(long births) {
            if (births <= 0) {
                return null;
            }
            int lo = 0;
            int hi = desc.length;   // count of entries > births
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (desc[mid] > births) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo + 1;
        }

        long births(String folded) {
            return byName.getOrDefault(folded, 0L);
        }
    }

    private Ranking ranking() {
        return cached("ranking", () -> {
            Map<String, Long> byName = new HashMap<>();
            jdbc.query("SELECT folded, SUM(ssa_male + ssa_female) AS births FROM given_names GROUP BY folded "
                + "HAVING SUM(ssa_male + ssa_female) > 0", rs -> {
                byName.put(rs.getString("folded"), rs.getLong("births"));
            });
            long[] desc = byName.values().stream().mapToLong(Long::longValue).sorted().toArray();
            for (int i = 0, j = desc.length - 1; i < j; i++, j--) {
                long t = desc[i];
                desc[i] = desc[j];
                desc[j] = t;
            }
            return new Ranking(desc, byName);
        });
    }

    // ---------------------------------------------------------------- benchmark

    /** Variety and unusual share of every film released in a year with enough people credited. */
    record YearScores(double[] variety, double[] unusual) {}

    private Benchmark benchmark(int year, double variety, double unusualShare) {
        YearScores scores = cached("year:" + year, () -> yearScores(year));
        if (scores.variety().length == 0) {
            return null;
        }
        return new Benchmark(year, scores.variety().length, round(mean(scores.variety())),
            round(percentile(scores.variety(), variety)), round(mean(scores.unusual())),
            round(percentile(scores.unusual(), unusualShare)));
    }

    private YearScores yearScores(int year) {
        Ranking ranking = ranking();
        // movie -> person -> folded first name
        Map<Long, Map<Long, String>> films = new HashMap<>();
        jdbc.query("SELECT c.movie_id, p.id AS person_id, p.first_name FROM credits c "
                + "JOIN people p ON p.id = c.person_id JOIN movies m ON m.id = c.movie_id "
                + "WHERE m.release_date >= ? AND m.release_date < ?",
            rs -> {
                String k = key(rs.getString("first_name"));
                if (k != null) {
                    films.computeIfAbsent(rs.getLong("movie_id"), x -> new HashMap<>()).put(rs.getLong("person_id"), k);
                }
            }, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
        List<double[]> out = new ArrayList<>();
        for (Map<Long, String> film : films.values()) {
            if (film.size() < BENCHMARK_MIN_PEOPLE) {
                continue;
            }
            long distinct = film.values().stream().distinct().count();
            long unusual = film.values().stream()
                .filter(k -> ranking.tier(ranking.births(k)).ordinal() >= Tier.UNCOMMON.ordinal()).count();
            out.add(new double[] {(double) distinct / film.size(), (double) unusual / film.size()});
        }
        return new YearScores(out.stream().mapToDouble(a -> a[0]).toArray(), out.stream().mapToDouble(a -> a[1]).toArray());
    }

    /** Share of values strictly below {@code value}. */
    static double percentile(double[] values, double value) {
        long below = Arrays.stream(values).filter(v -> v < value).count();
        return values.length == 0 ? 0 : (double) below / values.length;
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ---------------------------------------------------------------- helpers

    private static LocalDate toDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private static List<List<String>> chunks(Collection<String> all) {
        List<String> list = new ArrayList<>(all);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            out.add(list.subList(i, Math.min(list.size(), i + IN_CHUNK)));
        }
        return out;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private record Cached<T>(T value, Instant at) {}

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> load) {
        Cached<?> hit = cache.get(key);
        if (hit != null && hit.at().plus(CACHE_FOR).isAfter(Instant.now())) {
            return (T) hit.value();
        }
        T value = load.get();
        cache.put(key, new Cached<>(value, Instant.now()));
        return value;
    }

    /** Forget cached results, e.g. after new films or name counts are loaded. */
    public void clearCache() {
        cache.clear();
    }
}

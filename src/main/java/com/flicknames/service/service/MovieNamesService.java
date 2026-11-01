package com.flicknames.service.service;

import com.flicknames.service.util.charactername.CharacterNameLexicon;
import org.springframework.beans.factory.annotation.Value;
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
        NEVER_REGISTERED,
        /** Not given to any US baby and doesn't look like a given name: a compound, stage name or fragment */
        UNCLASSIFIED
    }

    static final int TOP_EVERYDAY = 100;
    static final int TOP_COMMON = 1000;
    static final long UNCOMMON_MIN_BIRTHS = 1000;
    /** "Top 20" names for the share of credits held by the very commonest names */
    static final int TOP_HEAVY = 20;
    /** Films with fewer people credited are left out of the year's benchmark */
    static final int BENCHMARK_MIN_PEOPLE = 20;
    /** A size band needs this many films to be the benchmark; otherwise the whole year is used */
    static final int BAND_MIN_FILMS = 20;
    /** People credited: bands for comparing films of similar size */
    static final int[] SIZE_BANDS = {20, 50, 100, 200, 400};
    /** Top-billed cast whose names (and whose characters' names) get births around release */
    static final int LEAD_MAX_ORDER = 3;
    static final int CHARACTER_MAX_ORDER = 6;
    /** Births around release: this many years either side */
    static final int AROUND_RELEASE = 2;
    /** Prefixes after which a capital is part of a given name (LaToya, DeShawn, McKenna) */
    private static final java.util.regex.Pattern INNER_CAPITAL_PREFIX =
        java.util.regex.Pattern.compile("^(Mc|Mac|De|Da|Di|Du|La|Le|Ja|Ke|Sha|Ta|Ty|D'|O')\\p{Lu}.*");
    private static final int IN_CHUNK = 500;
    private static final Duration CACHE_FOR = Duration.ofHours(12);

    private final JdbcTemplate jdbc;
    /** Screen debuts are only claimed for years with at least this many films in the catalogue */
    private final int debutMinFilmsInYear;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();

    public MovieNamesService(JdbcTemplate jdbc,
                             @Value("${flicknames.movie-names.debut-min-films:200}") int debutMinFilmsInYear) {
        this.jdbc = jdbc;
        this.debutMinFilmsInYear = debutMinFilmsInYear;
    }

    // ---------------------------------------------------------------- public view

    /**
     * {@code debutsReliable} is false when the catalogue holds fewer films from the release year than
     * {@code flicknames.movie-names.debut-min-films} (200); screen debuts are then not claimed ({@code debut} false, {@code debutNames} 0).
     */
    public record MovieNames(long movieId, String title, LocalDate releaseDate, LocalDate catalogFrom,
                             int catalogFilmsInYear, boolean debutsReliable, Summary summary, Benchmark benchmark,
                             List<NameEntry> names, List<CharacterEntry> characters,
                             List<ReleaseBirths> releaseBirths) {}

    /**
     * {@code variety} is distinct first names over people credited; {@code topHeavyShare} the share of people
     * whose name is one of the 20 most-given US names; {@code unusualShare} the share whose name is
     * {@link Tier#UNCOMMON} or rarer.
     */
    public record Summary(int people, int distinctNames, double variety, double topHeavyShare, double unusualShare,
                          Map<Tier, Integer> tiers, int debutNames, int neverRegisteredNames) {}

    /**
     * How this film compares with others released the same year ({@code percentile}: share of films below it).
     * Films with a similar number of people credited ({@code sizeFrom}..{@code sizeTo}) when there are enough
     * of them ({@code scope} SIZE_BAND), since bigger films repeat more names; else the whole year ({@code scope}
     * YEAR, {@code sizeTo} null).
     */
    public record Benchmark(int year, String scope, int sizeFrom, Integer sizeTo, int films, double averageVariety,
                            double varietyPercentile, double averageUnusualShare, double unusualPercentile) {}

    /**
     * One first name among the people credited. {@code firstSeen} is its earliest release date anywhere in
     * the catalogue (as a person or a character); {@code debut} is true when that's this film's release.
     */
    public record NameEntry(String name, int people, boolean cast, boolean crew, Tier tier, long usBirths,
                            Integer allTimeRank, int filmPeople, int filmCharacters, LocalDate firstSeen,
                            boolean debut) {}

    public record CharacterEntry(String character, String firstName, String actor, Integer castOrder, Tier tier,
                                 long usBirths, Integer allTimeRank, LocalDate firstSeen, boolean debut) {}

    /**
     * US births (both sexes) of a name in the years around release, for the names a film could have put in
     * parents' heads: its main characters, top-billed cast and directors. {@code role} is CHARACTER, LEAD or
     * DIRECTOR; {@code who} names the person (and character).
     */
    public record ReleaseBirths(String name, String role, String who, List<YearCount> births) {}

    public record YearCount(int year, long count) {}

    /** Empty when the movie doesn't exist. */
    public Optional<MovieNames> movieNames(long movieId) {
        return cached("movie:" + movieId, () -> compute(movieId));
    }

    // ---------------------------------------------------------------- computation

    private record CreditRow(long personId, String personFirst, String roleType, String characterFirst,
                             String characterName, Long characterId, String personName, Integer castOrder,
                             String job) {}

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
                   c.cast_order, c.job,
                   ch.id AS character_id, ch.first_name AS character_first, ch.full_name AS character_name
            FROM credits c JOIN people p ON p.id = c.person_id
            LEFT JOIN characters ch ON ch.id = c.character_id
            WHERE c.movie_id = ?""",
            (rs, i) -> new CreditRow(rs.getLong("person_id"), rs.getString("person_first"), rs.getString("role_type"),
                rs.getString("character_first"), rs.getString("character_name"),
                (Long) rs.getObject("character_id"), rs.getString("person_name"),
                (Integer) rs.getObject("cast_order"), rs.getString("job")), movieId);

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

        int filmsInYear = released == null ? 0 : filmsInYear(released.getYear());
        boolean debutsReliable = filmsInYear >= debutMinFilmsInYear;

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
            Tier tier = tierOf(ranking, kn.births(), display.get(k));
            Integer rank = ranking.rank(kn.births());
            LocalDate seen = firstSeen.get(k);
            boolean debut = debutsReliable && isDebut(seen, released) && tier != Tier.UNCLASSIFIED;
            names.add(new NameEntry(display.get(k), n, castNames.contains(k), crewNames.contains(k), tier,
                kn.births(), rank, kn.people(), kn.characters(), seen, debut));
            tiers.merge(tier, n, Integer::sum);
            if (rank != null && rank <= TOP_HEAVY) {
                topHeavy += n;
            }
            if (isUnusual(tier)) {
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
            Tier tier = tierOf(ranking, kn.births(), c.characterFirst().trim());
            characterEntries.add(new CharacterEntry(c.characterName(), c.characterFirst().trim(), c.personName(),
                c.castOrder(), tier, kn.births(), ranking.rank(kn.births()), seen,
                debutsReliable && isDebut(seen, released) && tier != Tier.UNCLASSIFIED));
        }
        characterEntries.sort(Comparator.comparing(CharacterEntry::castOrder, Comparator.nullsLast(Comparator.naturalOrder())));

        int total = people.size();
        double variety = total == 0 ? 0 : (double) peopleByName.size() / total;
        double unusualShare = total == 0 ? 0 : (double) unusual / total;
        Summary summary = new Summary(total, peopleByName.size(), round(variety), round(total == 0 ? 0 : (double) topHeavy / total),
            round(unusualShare), tiers, debuts, never);
        Benchmark benchmark = released == null || total < BENCHMARK_MIN_PEOPLE ? null
            : benchmark(released.getYear(), total, variety, unusualShare);

        return Optional.of(new MovieNames(movieId, title, released, catalogFrom(), filmsInYear, debutsReliable,
            summary, benchmark, names, characterEntries, releaseBirths(rows, released)));
    }

    private static boolean isDebut(LocalDate firstSeen, LocalDate released) {
        return released != null && firstSeen != null && !firstSeen.isBefore(released);
    }

    /** UNCOMMON, RARE or NEVER_REGISTERED; unclassified spellings don't count as unusual names. */
    static boolean isUnusual(Tier tier) {
        return tier == Tier.UNCOMMON || tier == Tier.RARE || tier == Tier.NEVER_REGISTERED;
    }

    /** The tier by births, except that an unregistered spelling that doesn't look like a given name is UNCLASSIFIED. */
    static Tier tierOf(Ranking ranking, long births, String spelling) {
        Tier tier = ranking.tier(births);
        return tier == Tier.NEVER_REGISTERED && !looksLikeGivenName(spelling) ? Tier.UNCLASSIFIED : tier;
    }

    /**
     * Whether a spelling reads as a single given name: at least three letters, only letters, hyphens and
     * apostrophes, no spaces, and no capital inside the word unless after a familiar prefix (LaToya, McKenna).
     * Catches compounds ("Leslie Ann"), stage names ("BloodPop") and fragments ("Mad" passes, "Ed" doesn't).
     */
    static boolean looksLikeGivenName(String spelling) {
        if (spelling == null) {
            return false;
        }
        String s = spelling.trim();
        if (s.codePointCount(0, s.length()) < 3 || !s.chars().allMatch(c -> Character.isLetter(c) || c == '-' || c == '\'')) {
            return false;
        }
        for (String part : s.split("-")) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (Character.isUpperCase(part.charAt(i)) && !INNER_CAPITAL_PREFIX.matcher(part).matches()) {
                    return false;
                }
            }
        }
        return true;
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

    /** Each film released in a year with enough people credited: people, variety and unusual share. */
    record YearScores(int[] people, double[] variety, double[] unusual) {}

    private Benchmark benchmark(int year, int people, double variety, double unusualShare) {
        YearScores scores = cached("year:" + year, () -> yearScores(year));
        if (scores.people().length == 0) {
            return null;
        }
        int[] band = sizeBand(people);
        List<Integer> inBand = new ArrayList<>();
        for (int i = 0; i < scores.people().length; i++) {
            int p = scores.people()[i];
            if (p >= band[0] && (band[1] < 0 || p < band[1])) {
                inBand.add(i);
            }
        }
        boolean useBand = inBand.size() >= BAND_MIN_FILMS;
        double[] v = useBand ? inBand.stream().mapToDouble(i -> scores.variety()[i]).toArray() : scores.variety();
        double[] u = useBand ? inBand.stream().mapToDouble(i -> scores.unusual()[i]).toArray() : scores.unusual();
        return new Benchmark(year, useBand ? "SIZE_BAND" : "YEAR", useBand ? band[0] : BENCHMARK_MIN_PEOPLE,
            useBand && band[1] >= 0 ? band[1] - 1 : null, v.length, round(mean(v)), round(percentile(v, variety)),
            round(mean(u)), round(percentile(u, unusualShare)));
    }

    /** The {@link #SIZE_BANDS} band a film of this many people falls in: {from, to (exclusive) or -1 for open}. */
    static int[] sizeBand(int people) {
        for (int i = SIZE_BANDS.length - 1; i >= 0; i--) {
            if (people >= SIZE_BANDS[i]) {
                return new int[] {SIZE_BANDS[i], i + 1 < SIZE_BANDS.length ? SIZE_BANDS[i + 1] : -1};
            }
        }
        return new int[] {0, SIZE_BANDS[0]};
    }

    private YearScores yearScores(int year) {
        Ranking ranking = ranking();
        // movie -> person -> first name as written
        Map<Long, Map<Long, String>> films = new HashMap<>();
        jdbc.query("SELECT c.movie_id, p.id AS person_id, p.first_name FROM credits c "
                + "JOIN people p ON p.id = c.person_id JOIN movies m ON m.id = c.movie_id "
                + "WHERE m.release_date >= ? AND m.release_date < ?",
            rs -> {
                String first = rs.getString("first_name");
                if (key(first) != null) {
                    films.computeIfAbsent(rs.getLong("movie_id"), x -> new HashMap<>()).put(rs.getLong("person_id"), first.trim());
                }
            }, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
        List<double[]> out = new ArrayList<>();
        for (Map<Long, String> film : films.values()) {
            if (film.size() < BENCHMARK_MIN_PEOPLE) {
                continue;
            }
            long distinct = film.values().stream().map(MovieNamesService::key).distinct().count();
            long unusual = film.values().stream()
                .filter(n -> isUnusual(tierOf(ranking, ranking.births(key(n)), n))).count();
            out.add(new double[] {film.size(), (double) distinct / film.size(), (double) unusual / film.size()});
        }
        return new YearScores(out.stream().mapToInt(a -> (int) a[0]).toArray(),
            out.stream().mapToDouble(a -> a[1]).toArray(), out.stream().mapToDouble(a -> a[2]).toArray());
    }

    private int filmsInYear(int year) {
        return cached("films:" + year, () -> {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM movies WHERE release_date >= ? AND release_date < ?",
                Integer.class, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
            return n == null ? 0 : n;
        });
    }

    // ---------------------------------------------------------------- births around release

    /**
     * Births around release for the main characters' first names, the top-billed cast's and the directors',
     * each name once (first role wins), leaving out names with no births in the window.
     */
    private List<ReleaseBirths> releaseBirths(List<CreditRow> rows, LocalDate released) {
        if (released == null) {
            return List.of();
        }
        Integer latest = latestSsaYear();
        if (latest == null) {
            return List.of();
        }
        int from = released.getYear() - AROUND_RELEASE;
        int to = Math.min(released.getYear() + AROUND_RELEASE, latest);
        if (from > to) {
            return List.of();
        }
        record Pick(String key, String name, String role, String who, int order) {}
        Map<String, Pick> picks = new LinkedHashMap<>();
        List<CreditRow> cast = rows.stream().filter(r -> "CAST".equals(r.roleType()) && r.castOrder() != null)
            .sorted(Comparator.comparing(CreditRow::castOrder)).toList();
        for (CreditRow r : cast) {
            String ck = key(r.characterFirst());
            if (ck != null && r.castOrder() <= CHARACTER_MAX_ORDER && looksLikeGivenName(r.characterFirst())) {
                picks.putIfAbsent(ck, new Pick(ck, r.characterFirst().trim(), "CHARACTER",
                    r.characterName() + " · " + r.personName(), 0));
            }
        }
        for (CreditRow r : cast) {
            String pk = key(r.personFirst());
            if (pk != null && r.castOrder() <= LEAD_MAX_ORDER) {
                picks.putIfAbsent(pk, new Pick(pk, r.personFirst().trim(), "LEAD", r.personName(), 1));
            }
        }
        for (CreditRow r : rows) {
            String pk = key(r.personFirst());
            if (pk != null && "Director".equalsIgnoreCase(r.job())) {
                picks.putIfAbsent(pk, new Pick(pk, r.personFirst().trim(), "DIRECTOR", r.personName(), 2));
            }
        }
        if (picks.isEmpty()) {
            return List.of();
        }
        Map<String, Map<Integer, Long>> births = new HashMap<>();
        List<String> keys = new ArrayList<>(picks.keySet());
        List<Object> args = new ArrayList<>(keys);
        args.add(from);
        args.add(to);
        jdbc.query("SELECT LOWER(n.name) AS n, y.year, SUM(y.count) AS c FROM ssa_names n "
                + "JOIN ssa_name_yearly_stats y ON y.ssa_name_id = n.id "
                + "WHERE LOWER(n.name) IN (" + placeholders(keys.size()) + ") AND y.year BETWEEN ? AND ? "
                + "GROUP BY LOWER(n.name), y.year",
            rs -> {
                births.computeIfAbsent(rs.getString("n"), k -> new HashMap<>()).put(rs.getInt("year"), rs.getLong("c"));
            }, args.toArray());
        List<ReleaseBirths> out = new ArrayList<>();
        for (Pick p : picks.values()) {
            Map<Integer, Long> b = births.get(p.key());
            if (b == null || b.isEmpty()) {
                continue;
            }
            List<YearCount> series = new ArrayList<>();
            for (int y = from; y <= to; y++) {
                series.add(new YearCount(y, b.getOrDefault(y, 0L)));
            }
            out.add(new ReleaseBirths(p.name(), p.role(), p.who(), series));
        }
        return out;
    }

    private Integer latestSsaYear() {
        return cached("latestSsaYear", () -> jdbc.queryForObject("SELECT MAX(year) FROM ssa_name_yearly_stats", Integer.class));
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

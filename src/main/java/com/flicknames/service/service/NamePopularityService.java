package com.flicknames.service.service;

import com.flicknames.service.research.dto.NameResearchDTO;
import com.flicknames.service.research.service.NameResearchService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A name's US baby-name popularity for its name page: every year with its rank, its share of births,
 * its rank in each state, counts for the names around it, and rising names that sound like it.
 *
 * <p>Ranks come from the stored rank when the ranking job has filled it, and are otherwise worked out
 * from the counts. Only the top 1,000 of each year (and each state) are ranked, as the SSA does. The
 * per-year leaderboards behind that are cached for a day, since the data only changes on import.
 */
@Service
public class NamePopularityService {

    /** Ranks past this are left null, like the SSA's published top 1,000 */
    static final int TOP = 1000;
    /** Rising names are compared with this many years earlier */
    static final int RISING_SPAN = 5;
    /** Related names are compared with this many years earlier */
    static final int RELATED_SPAN = 10;
    private static final Duration CACHE_FOR = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final NameFamilyService families;
    private final NameResearchService research;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();

    public NamePopularityService(JdbcTemplate jdbc, NameFamilyService families, NameResearchService research) {
        this.jdbc = jdbc;
        this.families = families;
        this.research = research;
    }

    // ---------------------------------------------------------------- public view

    public record Popularity(String name, String sex, int latestYear, List<YearStat> years, Share share,
                             StateRanks states, List<RelatedCount> related, List<Rising> rising) {}

    public record YearStat(int year, long count, Integer rank) {}

    public record Share(int year, long count, long births, long oneIn) {}

    public record StateRanks(int year, List<StateRank> states) {}

    public record StateRank(String state, long count, Integer rank, String leader) {}

    public record RelatedCount(String name, String sex, int year, long count, Integer rank,
                               int earlierYear, long earlierCount) {}

    public record Rising(String name, long count, Integer rank, int earlierYear, long earlierCount) {}

    /** Empty when the SSA has no record of the name. */
    public Optional<Popularity> popularity(String requested) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT n.id, n.name, n.sex, COALESCE(SUM(s.count), 0) AS total FROM ssa_names n "
                + "LEFT JOIN ssa_name_yearly_stats s ON s.ssa_name_id = n.id "
                + "WHERE LOWER(n.name) = LOWER(?) GROUP BY n.id, n.name, n.sex ORDER BY total DESC",
            requested.trim());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // A name used for both sexes is shown as the more common one
        Map<String, Object> primary = rows.get(0);
        long id = ((Number) primary.get("id")).longValue();
        String name = (String) primary.get("name");
        String sex = (String) primary.get("sex");

        List<YearStat> years = jdbc.query(
            "SELECT year, count, rank FROM ssa_name_yearly_stats WHERE ssa_name_id = ? ORDER BY year",
            (rs, i) -> new YearStat(rs.getInt("year"), rs.getLong("count"), (Integer) rs.getObject("rank")), id);
        if (years.isEmpty()) {
            return Optional.empty();
        }
        years = years.stream()
            .map(y -> y.rank() != null ? y : new YearStat(y.year(), y.count(), rankIn(national(y.year(), sex), y.count())))
            .toList();

        YearStat latest = years.get(years.size() - 1);
        long births = births(latest.year(), sex);
        Share share = births > 0 && latest.count() > 0
            ? new Share(latest.year(), latest.count(), births, Math.round((double) births / latest.count()))
            : null;

        Set<String> related = relatedNames(name, requested);
        return Optional.of(new Popularity(name, sex, latest.year(), years, share,
            stateRanks(id, sex), relatedCounts(related, latest.year()),
            rising(name, sex, latest.year(), related)));
    }

    // ---------------------------------------------------------------- national ranks and births

    /** A year's leaderboard for one sex: the top {@link #TOP} + 1 names, most common first. */
    record Entry(long ssaNameId, String name, long count) {}

    private List<Entry> national(int year, String sex) {
        return cached("national:" + year + ":" + sex, () -> jdbc.query(
            "SELECT n.id, n.name, s.count FROM ssa_name_yearly_stats s JOIN ssa_names n ON n.id = s.ssa_name_id "
                + "WHERE s.year = ? AND n.sex = ? ORDER BY s.count DESC LIMIT " + (TOP + 1),
            (rs, i) -> new Entry(rs.getLong("id"), rs.getString("name"), rs.getLong("count")), year, sex));
    }

    /** 1 + the number of names with a higher count (ties share a rank); null outside the top. */
    static Integer rankIn(List<Entry> board, long count) {
        return rankInCounts(board.stream().map(Entry::count).toList(), count);
    }

    private long births(int year, String sex) {
        return cached("births:" + year + ":" + sex, () -> {
            Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(s.count), 0) FROM ssa_name_yearly_stats s JOIN ssa_names n ON n.id = s.ssa_name_id "
                    + "WHERE s.year = ? AND n.sex = ?", Long.class, year, sex);
            return total == null ? 0L : total;
        });
    }

    // ---------------------------------------------------------------- states

    /** Each state's leaderboard for a year and sex: counts most common first, and the #1 name. */
    record StateBoard(List<Long> counts, String leader) {}

    private StateRanks stateRanks(long ssaNameId, String sex) {
        List<Map<String, Object>> own = jdbc.queryForList(
            "SELECT s.year, b.state_code, b.count, b.rank FROM ssa_name_state_breakdowns b "
                + "JOIN ssa_name_yearly_stats s ON s.id = b.yearly_stat_id "
                + "WHERE s.ssa_name_id = ? AND s.year = (SELECT MAX(s2.year) FROM ssa_name_yearly_stats s2 "
                + "JOIN ssa_name_state_breakdowns b2 ON b2.yearly_stat_id = s2.id WHERE s2.ssa_name_id = ?) "
                + "ORDER BY b.state_code", ssaNameId, ssaNameId);
        if (own.isEmpty()) {
            return null;
        }
        int year = ((Number) own.get(0).get("year")).intValue();
        Map<String, StateBoard> boards = stateBoards(year, sex);
        List<StateRank> states = new ArrayList<>();
        for (Map<String, Object> row : own) {
            String state = (String) row.get("state_code");
            long count = ((Number) row.get("count")).longValue();
            Integer rank = (Integer) row.get("rank");
            StateBoard board = boards.get(state);
            if (rank == null && board != null) {
                rank = rankInCounts(board.counts(), count);
            }
            states.add(new StateRank(state, count, rank, board == null ? null : board.leader()));
        }
        return new StateRanks(year, states);
    }

    private Map<String, StateBoard> stateBoards(int year, String sex) {
        return cached("states:" + year + ":" + sex, () -> {
            Map<String, List<Long>> counts = new HashMap<>();
            Map<String, String> leaders = new HashMap<>();
            jdbc.query("SELECT b.state_code, b.count, n.name FROM ssa_name_yearly_stats s "
                    + "JOIN ssa_names n ON n.id = s.ssa_name_id "
                    + "JOIN ssa_name_state_breakdowns b ON b.yearly_stat_id = s.id "
                    + "WHERE s.year = ? AND n.sex = ? ORDER BY b.state_code, b.count DESC",
                rs -> {
                    String state = rs.getString("state_code");
                    List<Long> list = counts.computeIfAbsent(state, k -> new ArrayList<>());
                    if (list.isEmpty()) {
                        leaders.put(state, rs.getString("name"));
                    }
                    if (list.size() <= TOP) {
                        list.add(rs.getLong("count"));
                    }
                }, year, sex);
            Map<String, StateBoard> boards = new HashMap<>();
            counts.forEach((state, list) -> boards.put(state, new StateBoard(List.copyOf(list), leaders.get(state))));
            return boards;
        });
    }

    /** {@code desc} holds at least the top {@link #TOP} + 1 counts, so anything past it is outside the top. */
    static Integer rankInCounts(List<Long> desc, long count) {
        int above = 0;
        for (long c : desc) {
            if (c <= count) {
                break;
            }
            above++;
        }
        return above < TOP ? above + 1 : null;
    }

    // ---------------------------------------------------------------- related names

    /** Spellings, short forms and research-related names of this name, excluding the name itself. */
    private Set<String> relatedNames(String name, String requested) {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (NameFamilyService.FamilyView f : families.page(requested).families()) {
                names.add(f.label());
                f.members().forEach(m -> names.add(m.name()));
            }
        } catch (RuntimeException e) {
            // No family data yet: research alone will do
        }
        research.getApprovedResearch(name).map(NameResearchDTO::getRelatedNames).ifPresent(list ->
            list.forEach(r -> names.add(r.getName())));
        names.removeIf(n -> n == null || n.isBlank() || n.equalsIgnoreCase(name) || n.equalsIgnoreCase(requested.trim()));
        return names;
    }

    private List<RelatedCount> relatedCounts(Collection<String> names, int year) {
        if (names.isEmpty()) {
            return List.of();
        }
        int earlier = year - RELATED_SPAN;
        List<String> lowered = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        String in = String.join(",", lowered.stream().map(n -> "?").toList());
        List<Object> args = new ArrayList<>(lowered);
        args.add(year);
        args.add(earlier);
        // name|sex -> {count now, count earlier, stored rank now}
        Map<String, Object[]> found = new LinkedHashMap<>();
        jdbc.query("SELECT n.name, n.sex, s.year, s.count, s.rank FROM ssa_names n "
                + "JOIN ssa_name_yearly_stats s ON s.ssa_name_id = n.id "
                + "WHERE LOWER(n.name) IN (" + in + ") AND s.year IN (?, ?)",
            rs -> {
                String key = rs.getString("name") + "|" + rs.getString("sex");
                Object[] v = found.computeIfAbsent(key, k -> new Object[] {0L, 0L, null});
                if (rs.getInt("year") == year) {
                    v[0] = rs.getLong("count");
                    v[2] = rs.getObject("rank");
                } else {
                    v[1] = rs.getLong("count");
                }
            }, args.toArray());

        List<RelatedCount> out = new ArrayList<>();
        found.forEach((key, v) -> {
            String[] parts = key.split("\\|");
            long now = (Long) v[0];
            Integer rank = (Integer) v[2];
            if (rank == null && now > 0) {
                rank = rankIn(national(year, parts[1]), now);
            }
            out.add(new RelatedCount(parts[0], parts[1], year, now, rank, earlier, (Long) v[1]));
        });
        out.sort(Comparator.comparingLong(RelatedCount::count).reversed());
        return out;
    }

    // ---------------------------------------------------------------- rising names

    /**
     * Names of the same sex in this year's top 500 that share the name's first or last two letters,
     * growing fastest over {@link #RISING_SPAN} years from at least 100 babies. At most eight.
     */
    private List<Rising> rising(String name, String sex, int year, Set<String> related) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.length() < 3) {
            return List.of();
        }
        String start = n.substring(0, 2);
        String end = n.substring(n.length() - 2);
        Set<String> skip = related.stream().map(r -> r.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        skip.add(n);

        List<Entry> board = national(year, sex);
        int earlierYear = year - RISING_SPAN;
        Map<Long, Long> earlier = national(earlierYear, sex).stream()
            .collect(Collectors.toMap(Entry::ssaNameId, Entry::count, (a, b) -> a));
        List<Rising> out = new ArrayList<>();
        for (int i = 0; i < Math.min(500, board.size()); i++) {
            Entry e = board.get(i);
            String c = e.name().toLowerCase(Locale.ROOT);
            if (skip.contains(c) || !(c.startsWith(start) || c.endsWith(end))) {
                continue;
            }
            Long before = earlier.get(e.ssaNameId());
            if (before == null || before < 100 || e.count() < before * 1.2) {
                continue;
            }
            out.add(new Rising(e.name(), e.count(), rankIn(board, e.count()), earlierYear, before));
        }
        out.sort(Comparator.comparingDouble((Rising r) -> -(double) r.count() / r.earlierCount()));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    // ---------------------------------------------------------------- cache

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

    /** Forget cached leaderboards, e.g. after an SSA import. */
    public void clearCache() {
        cache.clear();
    }
}

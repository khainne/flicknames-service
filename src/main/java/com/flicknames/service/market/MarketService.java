package com.flicknames.service.market;

import com.flicknames.service.entity.Credit;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read side of the name market: movers with week-over-week changes, history and comparisons. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketService {

    static final int SPARKLINE_WEEKS = 26;
    static final int MAX_LIMIT = 50;
    static final int MAX_COMPARE = 8;
    static final int DRIVERS_PER_NAME = 3;
    /** Ignore tiny names for gainers/losers, so a 0.01 to 0.05 jump isn't the top gainer. */
    static final double MIN_INDEX_FOR_MOVERS = 0.05;

    private final NameIndexWeekRepository repository;
    private final EntityManager entityManager;

    public Optional<MarketDtos.Movers> movers(Market market, int limit, LocalDate today) {
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        Optional<LocalDate> latest = repository.latestWeek(market);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        LocalDate week = latest.get();
        Map<String, NameIndexWeek> now = byKey(repository.findByMarketAndWeekStartOrderByRankPositionAsc(market, week));
        Map<String, NameIndexWeek> prev = byKey(
            repository.findByMarketAndWeekStartOrderByRankPositionAsc(market, week.minusWeeks(1)));
        Map<String, NameIndexWeek> prev6 = byKey(
            repository.findByMarketAndWeekStartOrderByRankPositionAsc(market, week.minusWeeks(6)));

        // Names that dropped out of the list since last week count as losers too.
        Set<String> keys = new LinkedHashSet<>(now.keySet());
        keys.addAll(prev.keySet());
        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            changes.add(change(key, now.get(key), prev.get(key), prev6.get(key)));
        }

        List<Change> top = changes.stream().filter(c -> c.current() != null)
            .sorted(Comparator.comparingInt(c -> c.current().getRankPosition())).limit(n).toList();
        List<Change> gainers = changes.stream()
            .filter(c -> c.change1w() > 0 && Math.max(c.index(), c.previousIndex()) >= MIN_INDEX_FOR_MOVERS)
            .sorted(Comparator.comparingDouble(Change::change1w).reversed()).limit(n).toList();
        List<Change> losers = changes.stream()
            .filter(c -> c.change1w() < 0 && Math.max(c.index(), c.previousIndex()) >= MIN_INDEX_FOR_MOVERS)
            .sorted(Comparator.comparingDouble(Change::change1w)).limit(n).toList();

        Set<String> shown = new LinkedHashSet<>();
        top.forEach(c -> shown.add(c.key()));
        gainers.forEach(c -> shown.add(c.key()));
        losers.forEach(c -> shown.add(c.key()));
        Map<String, List<Double>> sparks = sparklines(market, shown, week);
        LocalDate asOf = MarketWindow.asOf(week, today);
        Map<String, List<MarketDtos.Driver>> drivers = drivers(market, shown, asOf);

        Function<Change, MarketDtos.Quote> quote = c -> c.toQuote(sparks.get(c.key()), drivers.get(c.key()));
        return Optional.of(new MarketDtos.Movers(market, week, asOf, MarketWindow.WINDOW_DAYS,
            top.stream().map(quote).toList(), gainers.stream().map(quote).toList(),
            losers.stream().map(quote).toList()));
    }

    /** History per name, weekly or averaged per month or year; unranked weeks count as 0. */
    public Map<String, MarketDtos.History> history(Market market, Collection<String> names, LocalDate from,
                                                   LocalDate to, MarketDtos.Interval interval) {
        Map<String, MarketDtos.History> weekly = history(market, names, from, to);
        return interval == MarketDtos.Interval.WEEK ? weekly : aggregateAll(market, weekly, interval);
    }

    /** The most prominent names over a range (by mean weekly index) and their index per month or year. */
    public MarketDtos.Heatmap heatmap(Market market, LocalDate from, LocalDate to, MarketDtos.Interval interval,
                                      int top) {
        int n = Math.max(1, Math.min(top, MAX_LIMIT));
        LocalDate first = MarketWindow.weekStart(from);
        LocalDate last = MarketWindow.weekStart(to);
        long weeks = ChronoUnit.WEEKS.between(first, last) + 1;
        Map<String, Double> totals = new HashMap<>();
        for (NameIndexWeek r : repository.findByMarketAndWeekStartBetween(market, first, last)) {
            totals.merge(r.getNameKey(), r.getIndexValue(), Double::sum);
        }
        List<String> keys = totals.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .limit(n).map(Map.Entry::getKey).toList();
        MarketDtos.Interval step = interval == MarketDtos.Interval.WEEK ? MarketDtos.Interval.MONTH : interval;
        Map<String, MarketDtos.History> series = aggregateAll(market, weeklyHistory(market, keys, first, last), step);
        List<LocalDate> periods = series.isEmpty() ? List.of()
            : series.values().iterator().next().points().stream().map(MarketDtos.Point::week).toList();
        List<MarketDtos.HeatmapRow> rows = new ArrayList<>();
        for (String key : keys) {
            MarketDtos.History h = series.get(key);
            rows.add(new MarketDtos.HeatmapRow(h.name(), round(totals.get(key) / weeks),
                h.points().stream().map(MarketDtos.Point::index).toList()));
        }
        return new MarketDtos.Heatmap(market, step, periods, rows);
    }

    private static Map<String, MarketDtos.History> aggregateAll(Market market, Map<String, MarketDtos.History> weekly,
                                                                MarketDtos.Interval interval) {
        Map<String, MarketDtos.History> out = new LinkedHashMap<>();
        weekly.forEach((key, h) -> out.put(key, new MarketDtos.History(market, h.name(), aggregate(h.points(), interval))));
        return out;
    }

    /** Averages weekly points per calendar month or year; the rank is the best rank in the period. */
    static List<MarketDtos.Point> aggregate(List<MarketDtos.Point> weekly, MarketDtos.Interval interval) {
        Map<LocalDate, List<MarketDtos.Point>> byPeriod = new LinkedHashMap<>();
        for (MarketDtos.Point p : weekly) {
            LocalDate period = interval == MarketDtos.Interval.YEAR
                ? p.week().withDayOfYear(1) : p.week().withDayOfMonth(1);
            byPeriod.computeIfAbsent(period, k -> new ArrayList<>()).add(p);
        }
        List<MarketDtos.Point> out = new ArrayList<>();
        byPeriod.forEach((period, pts) -> {
            double avg = pts.stream().mapToDouble(MarketDtos.Point::index).average().orElse(0);
            Integer best = pts.stream().map(MarketDtos.Point::rank).filter(Objects::nonNull)
                .min(Integer::compare).orElse(null);
            out.add(new MarketDtos.Point(period, round(avg), best));
        });
        return out;
    }

    /** Weekly index per name between two dates; weeks a name wasn't in the top list read as 0. */
    public Map<String, MarketDtos.History> history(Market market, Collection<String> names, LocalDate from,
                                                   LocalDate to) {
        List<String> keys = names.stream().map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty())
            .distinct().limit(MAX_COMPARE).toList();
        return weeklyHistory(market, keys, from, to);
    }

    private Map<String, MarketDtos.History> weeklyHistory(Market market, List<String> keys, LocalDate from,
                                                          LocalDate to) {
        Map<String, MarketDtos.History> result = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        LocalDate first = MarketWindow.weekStart(from);
        LocalDate last = MarketWindow.weekStart(to);
        Map<String, List<NameIndexWeek>> rows = repository
            .findByMarketAndNameKeyInAndWeekStartBetweenOrderByWeekStartAsc(market, keys, first, last).stream()
            .collect(Collectors.groupingBy(NameIndexWeek::getNameKey));
        for (String key : keys) {
            List<NameIndexWeek> found = rows.getOrDefault(key, List.of());
            Map<LocalDate, NameIndexWeek> byWeek = found.stream()
                .collect(Collectors.toMap(NameIndexWeek::getWeekStart, r -> r));
            List<MarketDtos.Point> points = new ArrayList<>();
            for (LocalDate w = first; !w.isAfter(last); w = w.plusWeeks(1)) {
                NameIndexWeek r = byWeek.get(w);
                points.add(new MarketDtos.Point(w, r == null ? 0 : round(r.getIndexValue()),
                    r == null ? null : r.getRankPosition()));
            }
            String display = found.isEmpty() ? key : found.get(found.size() - 1).getName();
            result.put(key, new MarketDtos.History(market, display, points));
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, List<Double>> sparklines(Market market, Set<String> keys, LocalDate week) {
        Map<String, List<Double>> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        history(market, keys, week.minusWeeks(SPARKLINE_WEEKS - 1L), week)
            .forEach((k, h) -> out.put(k, h.points().stream().map(MarketDtos.Point::index).toList()));
        return out;
    }

    /** The films behind each name in the current window: the best credit per film, biggest first. */
    Map<String, List<MarketDtos.Driver>> drivers(Market market, Set<String> keys, LocalDate asOf) {
        Map<String, List<MarketDtos.Driver>> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        String q = market == Market.PERSON
            ? "SELECT LOWER(p.firstName), m.id, m.title, m.revenue, p.fullName, ch.fullName, cr.roleType, cr.job, "
                + "cr.order, p.fullName FROM Credit cr JOIN cr.person p JOIN cr.movie m LEFT JOIN cr.character ch "
                + "WHERE m.releaseDate BETWEEN :from AND :to AND m.revenue > 0 AND LOWER(p.firstName) IN :keys"
            : "SELECT LOWER(ch.firstName), m.id, m.title, m.revenue, ch.fullName, ch.fullName, cr.roleType, "
                + "cr.job, cr.order, p.fullName FROM Credit cr JOIN cr.character ch JOIN cr.person p "
                + "JOIN cr.movie m WHERE m.releaseDate BETWEEN :from AND :to AND m.revenue > 0 "
                + "AND LOWER(ch.firstName) IN :keys AND ch.nameType IN :types";
        var query = entityManager.createQuery(q, Object[].class)
            .setParameter("from", MarketWindow.windowStart(asOf))
            .setParameter("to", asOf)
            .setParameter("keys", keys);
        if (market == Market.CHARACTER) {
            query.setParameter("types", NameIndexService.CHARACTER_NAME_TYPES);
        }
        Map<String, Map<Long, Candidate>> best = new HashMap<>();
        for (Object[] r : query.getResultList()) {
            double revenue = ((BigDecimal) r[3]).doubleValue();
            Credit.RoleType roleType = (Credit.RoleType) r[6];
            double weight = CreditWeights.weight(roleType, (String) r[7], (Integer) r[8]);
            String role;
            if (market == Market.CHARACTER) {
                role = "Character";
            } else if (roleType == Credit.RoleType.CAST) {
                role = r[5] != null ? "as " + r[5] : "Actor";
            } else {
                role = (String) r[7];
            }
            String playedBy = market == Market.CHARACTER ? (String) r[9] : null;
            Candidate c = new Candidate(new MarketDtos.Driver((Long) r[1], (String) r[2], revenue, (String) r[4],
                role, playedBy), revenue * weight);
            best.computeIfAbsent((String) r[0], k -> new HashMap<>())
                .merge((Long) r[1], c, (a, b) -> b.score() > a.score() ? b : a);
        }
        best.forEach((key, films) -> out.put(key, films.values().stream()
            .sorted(Comparator.comparingDouble(Candidate::score).reversed())
            .limit(DRIVERS_PER_NAME).map(Candidate::driver).toList()));
        return out;
    }

    private record Candidate(MarketDtos.Driver driver, double score) {
    }

    private static Map<String, NameIndexWeek> byKey(List<NameIndexWeek> rows) {
        Map<String, NameIndexWeek> map = new LinkedHashMap<>();
        rows.forEach(r -> map.put(r.getNameKey(), r));
        return map;
    }

    private static Change change(String key, NameIndexWeek current, NameIndexWeek previous, NameIndexWeek sixAgo) {
        double index = current == null ? 0 : current.getIndexValue();
        double prevIndex = previous == null ? 0 : previous.getIndexValue();
        double sixIndex = sixAgo == null ? 0 : sixAgo.getIndexValue();
        return new Change(key, current, previous, index, prevIndex, index - prevIndex, index - sixIndex);
    }

    private record Change(String key, NameIndexWeek current, NameIndexWeek previous, double index,
                          double previousIndex, double change1w, double change6w) {

        MarketDtos.Quote toQuote(List<Double> sparkline, List<MarketDtos.Driver> drivers) {
            NameIndexWeek any = current != null ? current : previous;
            Double pct = previousIndex > 0 ? round(100.0 * change1w / previousIndex) : null;
            return new MarketDtos.Quote(any.getName(), round(index),
                current == null ? null : current.getRankPosition(), round(change1w), pct, round(change6w),
                current == null ? 0 : current.getMovieCount(),
                sparkline == null ? List.of() : sparkline, drivers == null ? List.of() : drivers);
        }
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

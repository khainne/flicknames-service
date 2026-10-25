package com.flicknames.service.service;

import com.flicknames.service.entity.NameTypeConverter;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The labeling queue: distinct raw character strings that have no label yet, most impactful first.
 *
 * <p>Impact is the summed revenue of the distinct movies the string appears in (a character
 * string credited twice in one movie counts that movie once), then the number of movies. One
 * aggregate query ranks the page; two more fetch the stored parse and example credits for just
 * that page. Labeled strings (including SKIPPED) drop out, so a labeler normally stays on offset 0.
 *
 * <p>The ranking itself is a full aggregate over credits (several seconds on ~2M credits), so the
 * top rows per filter are cached for {@link #RANKING_TTL} and labels are subtracted per request;
 * the window doubles when labels exhaust it. Example credits and the stored parse are always fresh.
 */
@Service
@RequiredArgsConstructor
public class CharacterNameLabelQueueService {

    public static final int MAX_LIMIT = 100;
    private static final int EXAMPLES_PER_NAME = 3;
    private static final NameTypeConverter NAME_TYPES = new NameTypeConverter();
    private static final int LABEL_CHECK_CHUNK = 500;
    private static final int MAX_CACHED_RANKINGS = 32;
    private static final Duration RANKING_TTL = Duration.ofMinutes(30);

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<QueueFilter, Ranking> rankings = new ConcurrentHashMap<>();

    /** Ranked rows fetched per filter at first; doubles whenever labels use them up. */
    @Value("${labels.queue.initial-window:2000}")
    private int initialWindow = 2000;

    /**
     * @param firstNames  only strings whose stored first_name is one of these (case-insensitive)
     * @param hasFirstName true: only strings with a stored first_name; false: only those without
     * @param nameTypes   only strings whose stored name_type is one of these
     * @param search      case-insensitive substring of the raw string
     */
    public record QueueFilter(List<String> firstNames, Boolean hasFirstName, List<NameType> nameTypes, String search) {}

    public record ExampleCredit(Long movieId, String title, Integer year, String originalLanguage, BigDecimal revenue,
                                String actorName, String actorGender) {}

    public record QueueItem(Long characterId, String fullName, String firstName, String lastName, NameType nameType,
                            int characterCount, long movieCount, long creditCount, BigDecimal totalRevenue,
                            List<ExampleCredit> examples) {}

    public record QueuePage(List<QueueItem> items, int offset, int limit, boolean hasMore) {}

    @Transactional(readOnly = true)
    public QueuePage next(QueueFilter filter, int offset, int limit) {
        return next(filter, offset, limit, false);
    }

    /** @param refresh recompute the ranking instead of using the cached one (e.g. after a collection run) */
    @Transactional(readOnly = true)
    public QueuePage next(QueueFilter filter, int offset, int limit, boolean refresh) {
        int size = Math.min(Math.max(1, limit), MAX_LIMIT);
        int start = Math.max(0, offset);
        QueueFilter key = normalize(filter);
        if (refresh) {
            rankings.remove(key);
        }

        // Walk the cached ranking, dropping labeled strings, until we have offset + size + 1.
        List<Ranked> unlabeled = new ArrayList<>();
        int scanned = 0;
        Ranking ranking = ranking(key, 0);
        while (unlabeled.size() < start + size + 1) {
            if (scanned >= ranking.rows().size()) {
                if (ranking.complete()) {
                    break;
                }
                ranking = ranking(key, ranking.rows().size() * 2);
                continue;
            }
            List<Ranked> chunk = ranking.rows().subList(scanned, Math.min(ranking.rows().size(), scanned + LABEL_CHECK_CHUNK));
            scanned += chunk.size();
            Set<String> labeled = new HashSet<>(jdbc.queryForList(
                "SELECT full_name FROM character_name_labels WHERE full_name IN (:names)",
                Map.of("names", chunk.stream().map(Ranked::fullName).toList()), String.class));
            chunk.stream().filter(r -> !labeled.contains(r.fullName())).forEach(unlabeled::add);
        }

        boolean hasMore = unlabeled.size() > start + size;
        List<Ranked> ranked = unlabeled.subList(Math.min(start, unlabeled.size()), Math.min(unlabeled.size(), start + size));
        if (ranked.isEmpty()) {
            return new QueuePage(List.of(), start, size, false);
        }

        List<String> names = ranked.stream().map(Ranked::fullName).toList();
        Map<String, Parse> parses = parses(names);
        Map<String, List<ExampleCredit>> examples = examples(names);

        List<QueueItem> items = ranked.stream().map(r -> {
            Parse p = parses.getOrDefault(r.fullName(), new Parse(r.characterId(), null, null, null, 1));
            return new QueueItem(p.characterId(), r.fullName(), p.firstName(), p.lastName(), p.nameType(),
                p.characterCount(), r.movieCount(), r.creditCount(), r.totalRevenue(),
                examples.getOrDefault(r.fullName(), List.of()));
        }).toList();
        return new QueuePage(items, start, size, hasMore);
    }

    /** Drops cached rankings, e.g. after labels were applied and stored first names changed. */
    public void invalidate() {
        rankings.clear();
    }

    private record Ranked(String fullName, long characterId, BigDecimal totalRevenue, long movieCount, long creditCount) {}

    /** The top {@code rows} of the impact ranking for one filter; complete when no further rows exist. */
    private record Ranking(List<Ranked> rows, boolean complete, Instant computedAt) {}

    private Ranking ranking(QueueFilter key, int minRows) {
        Ranking cached = rankings.get(key);
        boolean fresh = cached != null && cached.computedAt().isAfter(Instant.now().minus(RANKING_TTL));
        if (fresh && (cached.complete() || cached.rows().size() >= minRows)) {
            return cached;
        }
        int window = Math.max(Math.max(1, initialWindow), minRows);
        Ranking computed = computeRanking(key, window);
        if (computed.rows().isEmpty()) {
            // Nothing to cache (e.g. a fresh database still being seeded or collected); try again next time.
            rankings.remove(key);
            return computed;
        }
        if (rankings.size() >= MAX_CACHED_RANKINGS) {
            rankings.clear();
        }
        rankings.put(key, computed);
        return computed;
    }

    /**
     * Ranks distinct raw strings by summed revenue of their distinct movies. This is the expensive
     * part (a full aggregate over credits: seconds on the production tables), so results are cached per
     * filter and labeled strings are filtered out afterwards rather than in SQL.
     */
    private Ranking computeRanking(QueueFilter filter, int window) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", window + 1);
        StringBuilder where = new StringBuilder("1 = 1");
        if (!filter.firstNames().isEmpty()) {
            where.append(" AND LOWER(ch.first_name) IN (:firstNames)");
            params.addValue("firstNames", filter.firstNames());
        }
        if (filter.hasFirstName() != null) {
            where.append(filter.hasFirstName() ? " AND ch.first_name IS NOT NULL" : " AND ch.first_name IS NULL");
        }
        if (!filter.nameTypes().isEmpty()) {
            where.append(" AND ch.name_type IN (:nameTypes)");
            params.addValue("nameTypes", filter.nameTypes().stream().map(Enum::name).toList());
        }
        if (filter.search() != null) {
            where.append(" AND LOWER(ch.full_name) LIKE :search ESCAPE '\\'");
            params.addValue("search", "%" + escapeLike(filter.search()) + "%");
        }

        String sql = """
            WITH pairs AS (
                SELECT ch.full_name AS full_name, cr.movie_id AS movie_id,
                       MIN(ch.id) AS character_id, COUNT(*) AS credit_count
                FROM characters ch
                JOIN credits cr ON cr.character_id = ch.id
                WHERE %s
                GROUP BY ch.full_name, cr.movie_id
            )
            SELECT p.full_name AS full_name, MIN(p.character_id) AS character_id,
                   COALESCE(SUM(m.revenue), 0) AS total_revenue,
                   COUNT(*) AS movie_count, SUM(p.credit_count) AS credit_count
            FROM pairs p
            JOIN movies m ON m.id = p.movie_id
            GROUP BY p.full_name
            ORDER BY total_revenue DESC, movie_count DESC, p.full_name ASC
            LIMIT :limit
            """.formatted(where);

        List<Ranked> rows = jdbc.query(sql, params, (rs, i) -> new Ranked(
            rs.getString("full_name"), rs.getLong("character_id"), rs.getBigDecimal("total_revenue"),
            rs.getLong("movie_count"), rs.getLong("credit_count")));
        boolean complete = rows.size() <= window;
        return new Ranking(complete ? rows : List.copyOf(rows.subList(0, window)), complete, Instant.now());
    }

    private static QueueFilter normalize(QueueFilter filter) {
        List<String> firstNames = filter.firstNames() == null ? List.of() : filter.firstNames().stream()
            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).distinct().sorted().toList();
        List<NameType> types = filter.nameTypes() == null ? List.of() : filter.nameTypes().stream().distinct().sorted().toList();
        String search = filter.search() == null || filter.search().isBlank() ? null : filter.search().trim().toLowerCase(Locale.ROOT);
        return new QueueFilter(firstNames, filter.hasFirstName(), types, search);
    }

    private record Parse(Long characterId, String firstName, String lastName, NameType nameType, int characterCount) {}

    /** The stored parse of the lowest-id character per string (strings are normally one row each). */
    private Map<String, Parse> parses(List<String> names) {
        Map<String, Parse> result = new HashMap<>();
        jdbc.query("""
            SELECT id, full_name, first_name, last_name, name_type
            FROM characters
            WHERE full_name IN (:names)
            ORDER BY id
            """, Map.of("names", names), rs -> {
            String fullName = rs.getString("full_name");
            Parse existing = result.get(fullName);
            if (existing == null) {
                result.put(fullName, new Parse(rs.getLong("id"), rs.getString("first_name"), rs.getString("last_name"),
                    NAME_TYPES.convertToEntityAttribute(rs.getString("name_type")), 1));
            } else {
                result.put(fullName, new Parse(existing.characterId(), existing.firstName(), existing.lastName(),
                    existing.nameType(), existing.characterCount() + 1));
            }
        });
        return result;
    }

    /** Up to three highest-revenue movies per string, with the credited actor. */
    private Map<String, List<ExampleCredit>> examples(List<String> names) {
        Map<String, List<ExampleCredit>> result = new HashMap<>();
        Map<String, Set<Long>> seenMovies = new HashMap<>();
        jdbc.query("""
            SELECT x.full_name, x.movie_id, x.title, x.release_date, x.original_language, x.revenue,
                   x.actor_name, x.actor_gender
            FROM (
                SELECT ch.full_name AS full_name, m.id AS movie_id, m.title AS title, m.release_date AS release_date,
                       m.original_language AS original_language, m.revenue AS revenue,
                       p.full_name AS actor_name, p.gender AS actor_gender,
                       ROW_NUMBER() OVER (PARTITION BY ch.full_name
                                          ORDER BY COALESCE(m.revenue, 0) DESC, m.id ASC, cr.cast_order ASC) AS rn
                FROM characters ch
                JOIN credits cr ON cr.character_id = ch.id
                JOIN movies m ON m.id = cr.movie_id
                JOIN people p ON p.id = cr.person_id
                WHERE ch.full_name IN (:names)
            ) x
            WHERE x.rn <= :rows
            ORDER BY x.full_name, x.rn
            """, Map.of("names", names, "rows", EXAMPLES_PER_NAME * 3), rs -> {
            String fullName = rs.getString("full_name");
            long movieId = rs.getLong("movie_id");
            List<ExampleCredit> list = result.computeIfAbsent(fullName, k -> new ArrayList<>());
            if (list.size() >= EXAMPLES_PER_NAME || !seenMovies.computeIfAbsent(fullName, k -> new HashSet<>()).add(movieId)) {
                return;
            }
            Date released = rs.getDate("release_date");
            list.add(new ExampleCredit(movieId, rs.getString("title"),
                released == null ? null : released.toLocalDate().getYear(),
                rs.getString("original_language"), rs.getBigDecimal("revenue"),
                rs.getString("actor_name"), rs.getString("actor_gender")));
        });
        return result;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

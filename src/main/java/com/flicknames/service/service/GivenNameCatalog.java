package com.flicknames.service.service;

import com.flicknames.service.entity.GivenName.Quality;
import com.flicknames.service.entity.GivenNameLink.Source;
import com.flicknames.service.entity.GivenNameLink.Status;
import com.flicknames.service.entity.GivenNameLink.Type;
import com.flicknames.service.service.ResearchLinkRules.Candidate;
import com.flicknames.service.service.ResearchLinkRules.Relation;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Maintains given_names and given_name_links. {@link #refreshNames} upserts one row per spelling found in
 * people, characters, SSA, research and the compound list, with its counts; the link rebuilds replace one
 * source's rows at a time (never ADMIN's), so they can run whenever that source changes.
 */
@Service
@Slf4j
public class GivenNameCatalog {

    static final int MAX_SPELLING = 100;
    private static final int CHUNK = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public GivenNameCatalog(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record RebuildResult(long names, long namesAdded, long compoundLinks, long researchLinks, long millis) {}

    public synchronized RebuildResult rebuild() {
        long start = System.currentTimeMillis();
        long[] names = refreshNames();
        long compound = rebuildCompoundLinks();
        long research = rebuildResearchLinks();
        RebuildResult result = new RebuildResult(names[0], names[1], compound, research, System.currentTimeMillis() - start);
        log.info("Given-name catalog rebuilt: {}", result);
        return result;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onSourceChanged(GivenNameSourceChanged event) {
        try {
            if (event.source() == Source.RESEARCH) {
                rebuildResearchLinks();
            } else if (event.source() == Source.COMPOUND_LIST) {
                rebuildCompoundLinks();
            }
        } catch (DataAccessException e) {
            log.warn("Could not rebuild {} given-name links: {}", event.source(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- names

    private static final class Counts {
        int people, characters, peopleMale, peopleFemale;
        long ssaMale, ssaFemale;
        boolean researched;
    }

    /** {total names, names added} */
    public synchronized long[] refreshNames() {
        Map<String, Counts> counts = new HashMap<>();
        jdbc.query("""
            SELECT first_name, COUNT(*), SUM(CASE WHEN gender = 'Male' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN gender = 'Female' THEN 1 ELSE 0 END)
            FROM people WHERE first_name IS NOT NULL GROUP BY first_name""", rs -> {
            Counts c = counts(counts, rs.getString(1));
            if (c != null) {
                c.people = rs.getInt(2);
                c.peopleMale = rs.getInt(3);
                c.peopleFemale = rs.getInt(4);
            }
        });
        jdbc.query("SELECT first_name, COUNT(*) FROM characters WHERE first_name IS NOT NULL GROUP BY first_name", rs -> {
            Counts c = counts(counts, rs.getString(1));
            if (c != null) {
                c.characters = rs.getInt(2);
            }
        });
        try {
            jdbc.query("""
                SELECT n.name, n.sex, SUM(y.count) FROM ssa_names n
                JOIN ssa_name_yearly_stats y ON y.ssa_name_id = n.id GROUP BY n.name, n.sex""", rs -> {
                Counts c = counts(counts, rs.getString(1));
                if (c != null) {
                    if ("M".equals(rs.getString(2))) {
                        c.ssaMale += rs.getLong(3);
                    } else {
                        c.ssaFemale += rs.getLong(3);
                    }
                }
            });
        } catch (DataAccessException e) {
            log.warn("SSA counts unavailable for given names: {}", e.getMessage());
        }
        jdbc.query("SELECT name FROM name_research WHERE status = 'APPROVED'", rs -> {
            Counts c = counts(counts, rs.getString(1));
            if (c != null) {
                c.researched = true;
            }
        });
        jdbc.query("SELECT related_name FROM name_relationship", rs -> {
            counts(counts, rs.getString(1));
        });
        jdbc.query("SELECT name FROM compound_given_names", rs -> {
            counts(counts, rs.getString(1));
        });
        Set<String> compounds = new HashSet<>(jdbc.queryForList(
            "SELECT folded_name FROM compound_given_names WHERE enabled = TRUE", String.class));

        Map<String, Long> existing = ids();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        counts.forEach((spelling, c) -> {
            String folded = CharacterNameLexicon.fold(spelling);
            boolean compound = compounds.contains(folded);
            Quality quality = c.ssaMale + c.ssaFemale > 0 || c.people >= 2 || c.researched ? Quality.LEXICON : Quality.UNVERIFIED;
            Object[] values = {c.people, c.characters, c.ssaMale, c.ssaFemale, c.peopleMale, c.peopleFemale,
                compound, quality.name(), now};
            Long id = existing.get(spelling);
            if (id == null) {
                Object[] row = new Object[values.length + 2];
                row[0] = spelling;
                row[1] = folded;
                System.arraycopy(values, 0, row, 2, values.length);
                inserts.add(row);
            } else {
                Object[] row = Arrays.copyOf(values, values.length + 1);
                row[values.length] = id;
                updates.add(row);
            }
        });
        // Spellings no longer used anywhere keep their row (links may point at it) with zero counts
        Set<String> gone = new HashSet<>(existing.keySet());
        gone.removeAll(counts.keySet());
        for (String spelling : gone) {
            updates.add(new Object[] {0, 0, 0L, 0L, 0, 0, compounds.contains(CharacterNameLexicon.fold(spelling)),
                Quality.UNVERIFIED.name(), now, existing.get(spelling)});
        }
        batch("INSERT INTO given_names (spelling, folded, people_count, character_count, ssa_male, ssa_female, "
            + "people_male, people_female, compound, quality, refreshed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", inserts);
        batch("UPDATE given_names SET people_count = ?, character_count = ?, ssa_male = ?, ssa_female = ?, "
            + "people_male = ?, people_female = ?, compound = ?, quality = ?, refreshed_at = ? WHERE id = ?", updates);
        return new long[] {existing.size() + inserts.size(), inserts.size()};
    }

    private static Counts counts(Map<String, Counts> counts, String raw) {
        String spelling = raw == null ? "" : raw.trim();
        if (spelling.isEmpty() || spelling.length() > MAX_SPELLING) {
            return null;
        }
        return counts.computeIfAbsent(spelling, k -> new Counts());
    }

    private Map<String, Long> ids() {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, spelling FROM given_names", rs -> {
            ids.put(rs.getString(2), rs.getLong(1));
        });
        return ids;
    }

    /** Ids for these spellings, adding a row (no counts yet) for any not held. */
    private Map<String, Long> ensure(Collection<String> spellings) {
        Map<String, Long> ids = ids();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> inserts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : spellings) {
            String s = raw == null ? "" : raw.trim();
            if (!s.isEmpty() && s.length() <= MAX_SPELLING && !ids.containsKey(s) && seen.add(s)) {
                inserts.add(new Object[] {s, CharacterNameLexicon.fold(s), Quality.UNVERIFIED.name(), now});
            }
        }
        if (!inserts.isEmpty()) {
            batch("INSERT INTO given_names (spelling, folded, people_count, character_count, ssa_male, ssa_female, "
                + "people_male, people_female, compound, quality, refreshed_at) VALUES (?, ?, 0, 0, 0, 0, 0, 0, FALSE, ?, ?)",
                inserts);
            ids = ids();
        }
        return ids;
    }

    // ---------------------------------------------------------------- links

    /** COMPOUND_OF from each enabled compound to each of its words: Mary Kate -> Mary, Mary Kate -> Kate. */
    public synchronized long rebuildCompoundLinks() {
        List<Object[]> compounds = jdbc.query("SELECT id, name FROM compound_given_names WHERE enabled = TRUE",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)});
        List<Candidate> links = new ArrayList<>();
        for (Object[] c : compounds) {
            String name = ((String) c[1]).trim();
            for (String word : new LinkedHashSet<>(Arrays.asList(name.split("\\s+")))) {
                links.add(new Candidate(name, word, Type.COMPOUND_OF, Status.ACCEPTED, 100, "compound:" + c[0]));
            }
        }
        return replace(Source.COMPOUND_LIST, links);
    }

    /** Links from approved research cards, by {@link ResearchLinkRules}. */
    public synchronized long rebuildResearchLinks() {
        List<Relation> relations = jdbc.query("""
            SELECT r.id, r.name, rel.related_name, rel.relationship_type
            FROM name_research r JOIN name_relationship rel ON rel.name_research_id = r.id
            WHERE r.status = 'APPROVED'""",
            (rs, i) -> new Relation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        Map<String, long[]> ssa = new HashMap<>();
        jdbc.query("SELECT spelling, ssa_male, ssa_female FROM given_names", rs -> {
            ssa.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
        });
        List<Candidate> links = ResearchLinkRules.derive(relations, new ResearchLinkRules.SexCounts() {
            public long male(String s) {
                return ssa.getOrDefault(s.trim(), new long[2])[0];
            }

            public long female(String s) {
                return ssa.getOrDefault(s.trim(), new long[2])[1];
            }
        });
        return replace(Source.RESEARCH, links);
    }

    /** Replaces one source's links, keeping the most confident of duplicates. */
    private long replace(Source source, List<Candidate> candidates) {
        Set<String> spellings = new HashSet<>();
        candidates.forEach(c -> {
            spellings.add(c.from());
            spellings.add(c.to());
        });
        Map<String, Long> ids = ensure(spellings);
        Map<String, Object[]> rows = new LinkedHashMap<>();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Candidate c : candidates) {
            Long from = ids.get(c.from().trim());
            Long to = ids.get(c.to().trim());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            if (c.type().symmetric() && from > to) {
                Long t = from;
                from = to;
                to = t;
            }
            String key = from + "|" + to + "|" + c.type();
            Object[] row = {from, to, c.type().name(), source.name(), c.status().name(), c.confidence(), c.evidenceRef(), now};
            Object[] kept = rows.get(key);
            if (kept == null || better(row, kept)) {
                rows.put(key, row);
            }
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM given_name_links WHERE source = ?", source.name());
            batch("INSERT INTO given_name_links (from_id, to_id, type, source, status, confidence, evidence_ref, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", new ArrayList<>(rows.values()));
        });
        return rows.size();
    }

    private static boolean better(Object[] row, Object[] kept) {
        boolean accepted = Status.ACCEPTED.name().equals(row[4]);
        boolean keptAccepted = Status.ACCEPTED.name().equals(kept[4]);
        return accepted != keptAccepted ? accepted : (int) row[5] > (int) kept[5];
    }

    // ---------------------------------------------------------------- admin

    public record LinkView(long id, String from, String to, String type, String source, String status, int confidence,
                           String evidenceRef) {}

    public record NameView(Map<String, Object> name, List<String> sameFoldedSpellings, List<LinkView> links) {}

    private static final String LINK_SELECT = """
        SELECT l.id, f.spelling, t.spelling, l.type, l.source, l.status, l.confidence, l.evidence_ref
        FROM given_name_links l JOIN given_names f ON f.id = l.from_id JOIN given_names t ON t.id = l.to_id
        """;

    public Optional<NameView> name(String spelling) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM given_names WHERE spelling = ?", spelling.trim());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        List<String> same = jdbc.queryForList("SELECT spelling FROM given_names WHERE folded = ? AND id <> ? ORDER BY spelling",
            String.class, row.get("folded"), id);
        List<LinkView> links = jdbc.query(LINK_SELECT + " WHERE l.from_id = ? OR l.to_id = ? ORDER BY l.type, l.source",
            GivenNameCatalog::link, id, id);
        return Optional.of(new NameView(row, same, links));
    }

    public List<LinkView> links(Status status, Type type, Source source, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND l.status = ?");
            args.add(status.name());
        }
        if (type != null) {
            where.append(" AND l.type = ?");
            args.add(type.name());
        }
        if (source != null) {
            where.append(" AND l.source = ?");
            args.add(source.name());
        }
        args.add(Math.max(1, Math.min(limit, 1000)));
        return jdbc.query(LINK_SELECT + where + " ORDER BY t.spelling, f.spelling LIMIT ?", GivenNameCatalog::link,
            args.toArray());
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("names", jdbc.queryForObject("SELECT COUNT(*) FROM given_names", Long.class));
        Map<String, Long> byQuality = new TreeMap<>();
        jdbc.query("SELECT quality, COUNT(*) FROM given_names GROUP BY quality", rs -> {
            byQuality.put(rs.getString(1), rs.getLong(2));
        });
        out.put("namesByQuality", byQuality);
        Map<String, Long> links = new TreeMap<>();
        jdbc.query("SELECT source, type, status, COUNT(*) FROM given_name_links GROUP BY source, type, status", rs -> {
            links.put(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3), rs.getLong(4));
        });
        out.put("links", links);
        return out;
    }

    /** An admin decision on a link, kept across rebuilds; it overrides the other sources for that pair and type. */
    public LinkView decide(String from, String to, Type type, Status status) {
        if (status == Status.PROPOSED) {
            throw new IllegalArgumentException("An admin decision is ACCEPTED or REJECTED.");
        }
        Map<String, Long> ids = ensure(List.of(from, to));
        Long f = ids.get(from.trim());
        Long t = ids.get(to.trim());
        if (f == null || t == null || f.equals(t)) {
            throw new IllegalArgumentException("Two different names of at most " + MAX_SPELLING + " characters are needed.");
        }
        if (type.symmetric() && f > t) {
            Long x = f;
            f = t;
            t = x;
        }
        Long fromId = f;
        Long toId = t;
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM given_name_links WHERE from_id = ? AND to_id = ? AND type = ? AND source = 'ADMIN'",
                fromId, toId, type.name());
            jdbc.update("INSERT INTO given_name_links (from_id, to_id, type, source, status, confidence, evidence_ref, "
                + "created_at) VALUES (?, ?, ?, 'ADMIN', ?, 100, 'admin', ?)", fromId, toId, type.name(), status.name(),
                Timestamp.valueOf(LocalDateTime.now()));
        });
        return jdbc.query(LINK_SELECT + " WHERE l.from_id = ? AND l.to_id = ? AND l.type = ? AND l.source = 'ADMIN'",
            GivenNameCatalog::link, fromId, toId, type.name()).get(0);
    }

    private static LinkView link(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new LinkView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getInt(7), rs.getString(8));
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += CHUNK) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(rows.size(), i + CHUNK)));
        }
    }
}

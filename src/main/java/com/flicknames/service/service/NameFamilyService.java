package com.flicknames.service.service;

import com.flicknames.service.entity.DiminutiveUsage.Usage;
import com.flicknames.service.service.DiminutiveUsageModel.Pair;
import com.flicknames.service.service.NameFamilyRules.Family;
import com.flicknames.service.service.NameFamilyRules.Link;
import com.flicknames.service.service.NameFamilyRules.Member;
import com.flicknames.service.service.NameFamilyRules.Name;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Name families over the given-name catalog: classifies short forms as nickname or independent name
 * (Claude, overridable by an admin), rebuilds the families with {@link NameFamilyRules} and precomputes
 * their totals. Not shown on the public site yet.
 */
@Service
@Slf4j
@SuppressWarnings("unchecked")
public class NameFamilyService {

    private static final int CHUNK = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DiminutiveUsageModel model;

    public NameFamilyService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, DiminutiveUsageModel model) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.model = model;
    }

    /** An accepted DIMINUTIVE_OF or SPELLING_VARIANT link, an ADMIN decision winning over the other sources. */
    private record Effective(long from, long to, String type) {}

    private List<Effective> effectiveLinks() {
        Map<String, String[]> decided = new HashMap<>();   // key -> {status, isAdmin}
        jdbc.query("SELECT from_id, to_id, type, source, status FROM given_name_links "
            + "WHERE type IN ('DIMINUTIVE_OF', 'SPELLING_VARIANT')", rs -> {
            String key = rs.getLong(1) + "|" + rs.getLong(2) + "|" + rs.getString(3);
            boolean admin = "ADMIN".equals(rs.getString(4));
            String status = rs.getString(5);
            String[] kept = decided.get(key);
            if (kept == null || admin || (!"true".equals(kept[1]) && "ACCEPTED".equals(status))) {
                decided.put(key, new String[] {status, String.valueOf(admin)});
            }
        });
        List<Effective> out = new ArrayList<>();
        decided.forEach((key, v) -> {
            if ("ACCEPTED".equals(v[0])) {
                String[] k = key.split("\\|");
                out.add(new Effective(Long.parseLong(k[0]), Long.parseLong(k[1]), k[2]));
            }
        });
        return out;
    }

    // ---------------------------------------------------------------- usage

    public record ClassifyResult(long pairs, long classified, long nickname, long independent, long requests,
                                 long inputTokens, long outputTokens, List<String> errors) {}

    /**
     * Asks Claude about accepted short forms with no usage decision yet, at most {@code limit} of them. With
     * {@code redo}, the model's earlier decisions are dropped first (an admin's are kept).
     */
    public synchronized ClassifyResult classifyShortForms(int limit, boolean redo) {
        if (!model.configured()) {
            throw new IllegalStateException("The Claude API key is not set.");
        }
        if (redo) {
            jdbc.update("DELETE FROM diminutive_usages WHERE source = 'MODEL'");
        }
        Map<Long, String> spelling = new HashMap<>();
        jdbc.query("SELECT id, spelling FROM given_names WHERE id IN (SELECT from_id FROM given_name_links "
            + "UNION SELECT to_id FROM given_name_links)", rs -> {
            spelling.put(rs.getLong(1), rs.getString(2));
        });
        Set<String> known = new HashSet<>(jdbc.queryForList(
            "SELECT short_folded || '|' || full_folded FROM diminutive_usages", String.class));
        List<Pair> pending = new ArrayList<>();
        Map<Long, String[]> keys = new HashMap<>();
        Set<String> queued = new HashSet<>();
        for (Effective e : effectiveLinks()) {
            if (!e.type().equals("DIMINUTIVE_OF") || pending.size() >= limit) {
                continue;
            }
            String s = fold(spelling.get(e.from()));
            String f = fold(spelling.get(e.to()));
            if (known.contains(s + "|" + f) || !queued.add(s + "|" + f)) {
                continue;
            }
            long id = pending.size() + 1;
            pending.add(new Pair(id, spelling.get(e.from()), spelling.get(e.to())));
            keys.put(id, new String[] {s, f});
        }
        long classified = 0, nickname = 0, independent = 0, requests = 0, in = 0, out = 0;
        List<String> errors = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int i = 0; i < pending.size(); i += DiminutiveUsageModel.PAIRS_PER_REQUEST) {
            List<Pair> chunk = pending.subList(i, Math.min(pending.size(), i + DiminutiveUsageModel.PAIRS_PER_REQUEST));
            DiminutiveUsageModel.Result result = model.classify(chunk);
            requests++;
            in += result.inputTokens();
            out += result.outputTokens();
            if (result.error() != null) {
                errors.add(result.error());
            }
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<Long, Usage> u : result.usages().entrySet()) {
                String[] k = keys.get(u.getKey());
                rows.add(new Object[] {k[0], k[1], u.getValue().name(), now});
                if (u.getValue() == Usage.NICKNAME) {
                    nickname++;
                } else {
                    independent++;
                }
            }
            if (!rows.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO diminutive_usages (short_folded, full_folded, name_usage, source, decided_at) "
                    + "VALUES (?, ?, ?, 'MODEL', ?)", rows);
            }
            classified += rows.size();
        }
        ClassifyResult result = new ClassifyResult(pending.size(), classified, nickname, independent, requests, in, out, errors);
        log.info("Short-form usage classified: {}", result);
        return result;
    }

    /** An admin's usage decision for a short form and its full form; it replaces the model's. */
    public void decideUsage(String shortForm, String fullForm, Usage usage) {
        String s = fold(shortForm);
        String f = fold(fullForm);
        tx.executeWithoutResult(t -> {
            jdbc.update("DELETE FROM diminutive_usages WHERE short_folded = ? AND full_folded = ?", s, f);
            jdbc.update("INSERT INTO diminutive_usages (short_folded, full_folded, name_usage, source, decided_at) "
                + "VALUES (?, ?, ?, 'ADMIN', ?)", s, f, usage.name(), Timestamp.valueOf(LocalDateTime.now()));
        });
    }

    public List<Map<String, Object>> usages(Usage usage, int limit) {
        return jdbc.queryForList("SELECT short_folded, full_folded, name_usage, source, decided_at FROM diminutive_usages "
            + (usage == null ? "" : "WHERE name_usage = ? ") + "ORDER BY full_folded, short_folded LIMIT ?",
            usage == null ? new Object[] {Math.min(limit, 2000)} : new Object[] {usage.name(), Math.min(limit, 2000)});
    }

    // ---------------------------------------------------------------- families

    public record RebuildResult(long families, long members, long sharedMembers, long independentLeftOut, long millis) {}

    public synchronized RebuildResult rebuild() {
        long start = System.currentTimeMillis();
        List<Effective> links = effectiveLinks();
        Set<Long> ids = new HashSet<>();
        links.forEach(l -> {
            ids.add(l.from());
            ids.add(l.to());
        });
        // Linked names, plus every spelling that folds like one (José with Jose)
        Map<Long, Name> all = new HashMap<>();
        List<Name> catalog = jdbc.query("SELECT id, spelling, folded, people_count + character_count FROM given_names",
            (rs, i) -> new Name(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
        Set<String> linkedFolded = new HashSet<>();
        catalog.stream().filter(n -> ids.contains(n.id())).forEach(n -> linkedFolded.add(n.folded()));
        catalog.stream().filter(n -> linkedFolded.contains(n.folded())).forEach(n -> all.put(n.id(), n));
        Map<String, Usage> usage = new HashMap<>();
        Map<String, Boolean> admin = new HashMap<>();
        jdbc.query("SELECT short_folded, full_folded, name_usage, source FROM diminutive_usages", rs -> {
            String key = rs.getString(1) + "|" + rs.getString(2);
            boolean isAdmin = "ADMIN".equals(rs.getString(4));
            if (!admin.getOrDefault(key, false) || isAdmin) {
                usage.put(key, Usage.valueOf(rs.getString(3)));
                admin.put(key, isAdmin);
            }
        });
        List<Link> diminutives = new ArrayList<>();
        List<Link> variants = new ArrayList<>();
        long independent = 0;
        for (Effective l : links) {
            Name from = all.get(l.from());
            Name to = all.get(l.to());
            if (from == null || to == null) {
                continue;
            }
            if (l.type().equals("SPELLING_VARIANT")) {
                variants.add(new Link(l.from(), l.to()));
            } else if (usage.get(from.folded() + "|" + to.folded()) == Usage.INDEPENDENT) {
                independent++;
            } else {
                diminutives.add(new Link(l.from(), l.to()));
            }
        }
        List<Family> families = NameFamilyRules.build(all.values(), diminutives, variants);
        long[] counts = write(families);
        computeTotals();
        RebuildResult result = new RebuildResult(families.size(), counts[0], counts[1], independent,
            System.currentTimeMillis() - start);
        log.info("Name families rebuilt: {}", result);
        return result;
    }

    private long[] write(List<Family> families) {
        long[] counts = new long[2];
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        tx.executeWithoutResult(t -> {
            jdbc.update("DELETE FROM name_family_members");
            jdbc.update("DELETE FROM name_families");
            List<Object[]> familyRows = new ArrayList<>();
            for (Family f : families) {
                familyRows.add(new Object[] {f.headId(), f.label(), f.members().size(), now});
            }
            batch("INSERT INTO name_families (head_id, label, members, people, people_movies, characters, "
                + "character_movies, rebuilt_at) VALUES (?, ?, ?, 0, 0, 0, 0, ?)", familyRows);
            Map<Long, Long> familyIds = new HashMap<>();
            jdbc.query("SELECT id, head_id FROM name_families", rs -> {
                familyIds.put(rs.getLong(2), rs.getLong(1));
            });
            List<Object[]> memberRows = new ArrayList<>();
            for (Family f : families) {
                long familyId = familyIds.get(f.headId());
                for (Member m : f.members()) {
                    memberRows.add(new Object[] {familyId, m.nameId(), m.role().name(), m.shared()});
                    counts[0]++;
                    if (m.shared()) {
                        counts[1]++;
                    }
                }
            }
            batch("INSERT INTO name_family_members (family_id, given_name_id, role, shared) VALUES (?, ?, ?, ?)", memberRows);
        });
        return counts;
    }

    /** Distinct people, characters and their movies over each family's unshared members. */
    private void computeTotals() {
        List<Object[]> people = jdbc.query("""
            SELECT m.family_id, COUNT(DISTINCT p.id), COUNT(DISTINCT cr.movie_id)
            FROM name_family_members m
            JOIN given_names g ON g.id = m.given_name_id
            JOIN people p ON p.first_name = g.spelling
            LEFT JOIN credits cr ON cr.person_id = p.id
            WHERE m.shared = FALSE GROUP BY m.family_id""",
            (rs, i) -> new Object[] {rs.getLong(2), rs.getLong(3), rs.getLong(1)});
        List<Object[]> characters = jdbc.query("""
            SELECT m.family_id, COUNT(DISTINCT ch.id), COUNT(DISTINCT cr.movie_id)
            FROM name_family_members m
            JOIN given_names g ON g.id = m.given_name_id
            JOIN characters ch ON ch.first_name = g.spelling
            LEFT JOIN credits cr ON cr.character_id = ch.id
            WHERE m.shared = FALSE GROUP BY m.family_id""",
            (rs, i) -> new Object[] {rs.getLong(2), rs.getLong(3), rs.getLong(1)});
        batch("UPDATE name_families SET people = ?, people_movies = ? WHERE id = ?", people);
        batch("UPDATE name_families SET characters = ?, character_movies = ? WHERE id = ?", characters);
    }

    // ---------------------------------------------------------------- views

    public List<Map<String, Object>> familiesOf(String spelling) {
        List<Map<String, Object>> families = jdbc.queryForList("""
            SELECT f.id, f.label, f.members, f.people, f.people_movies, f.characters, f.character_movies, m.role, m.shared
            FROM name_families f
            JOIN name_family_members m ON m.family_id = f.id
            JOIN given_names g ON g.id = m.given_name_id
            WHERE g.folded = ? ORDER BY f.label""", fold(spelling));
        // One row per family, though several of its spellings fold alike (Jose, José)
        Set<Object> seen = new HashSet<>();
        families.removeIf(f -> !seen.add(f.get("id")));
        for (Map<String, Object> f : families) {
            f.put("memberList", jdbc.queryForList("""
                SELECT g.spelling, m.role, m.shared, g.people_count, g.character_count
                FROM name_family_members m JOIN given_names g ON g.id = m.given_name_id
                WHERE m.family_id = ? ORDER BY m.role, g.people_count + g.character_count DESC""", f.get("id")));
        }
        return families;
    }

    public List<Map<String, Object>> largest(int limit) {
        return jdbc.queryForList("SELECT id, label, members, people, people_movies, characters, character_movies "
            + "FROM name_families ORDER BY people + characters DESC LIMIT ?", Math.max(1, Math.min(limit, 500)));
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += CHUNK) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(rows.size(), i + CHUNK)));
        }
    }

    private static String fold(String s) {
        return CharacterNameLexicon.fold(s == null ? "" : s.trim());
    }

    // ---------------------------------------------------------------- public view

    /** A member as a name page shows it: spellings that fold alike are one entry, under the most common. */
    public record MemberView(String name, String role, boolean shared, long people, long characters) {}

    public record FamilyView(String label, String role, boolean shared, long people, long peopleMovies,
                             long characters, long characterMovies, List<MemberView> members) {}

    /** {@code name} is the most common spelling; {@code spellings} are all that fold like it (José, Jose). */
    public record FamilyPage(String name, List<String> spellings, List<FamilyView> families) {}

    public FamilyPage page(String requested) {
        String folded = fold(requested);
        List<String> spellings = jdbc.queryForList("SELECT spelling FROM given_names WHERE folded = ? "
            + "AND people_count + character_count > 0 ORDER BY people_count + character_count DESC, spelling",
            String.class, folded);
        String name = spellings.isEmpty() ? requested.trim() : spellings.get(0);
        List<FamilyView> views = new ArrayList<>();
        for (Map<String, Object> f : familiesOf(requested)) {
            Map<String, long[]> counts = new LinkedHashMap<>();   // folded -> {people, characters}
            Map<String, String> label = new HashMap<>();
            Map<String, String> role = new HashMap<>();
            Map<String, Boolean> shared = new HashMap<>();
            for (Map<String, Object> m : (List<Map<String, Object>>) f.get("memberList")) {
                String spelling = (String) m.get("spelling");
                String key = fold(spelling);
                long people = ((Number) m.get("people_count")).longValue();
                long characters = ((Number) m.get("character_count")).longValue();
                long[] c = counts.computeIfAbsent(key, k -> new long[2]);
                if (!label.containsKey(key) || people + characters > c[0] + c[1]) {
                    label.put(key, spelling);
                }
                c[0] += people;
                c[1] += characters;
                String r = (String) m.get("role");
                if (!"HEAD".equals(role.get(key))) {
                    role.put(key, r);
                }
                shared.merge(key, Boolean.TRUE.equals(m.get("shared")), Boolean::logicalOr);
            }
            List<MemberView> members = new ArrayList<>();
            counts.forEach((key, c) -> {
                if (c[0] + c[1] > 0) {
                    members.add(new MemberView(label.get(key), role.get(key), shared.get(key), c[0], c[1]));
                }
            });
            members.sort(Comparator.comparing((MemberView m) -> !"HEAD".equals(m.role()))
                .thenComparing(m -> -(m.people() + m.characters())));
            views.add(new FamilyView((String) f.get("label"), role.getOrDefault(folded, (String) f.get("role")),
                shared.getOrDefault(folded, false), num(f.get("people")), num(f.get("people_movies")),
                num(f.get("characters")), num(f.get("character_movies")), members));
        }
        return new FamilyPage(name, spellings.isEmpty() ? List.of(name) : spellings, views);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}

package com.flicknames.service.service;

import com.anthropic.models.messages.batches.MessageBatch;
import com.flicknames.service.entity.CharacterReparseRow.Source;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.service.CharacterNameModel.Answer;
import com.flicknames.service.service.CharacterNameModel.Decision;
import com.flicknames.service.service.CharacterNameModel.Item;
import com.flicknames.service.service.CharacterNameReparseService.Planned;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import com.flicknames.service.util.charactername.CharacterNameParserV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * The model pass over character credits the parser is unsure of, in three explicit steps that end in
 * the re-parse staging table, so the usual apply / revert do the writing:
 *
 * <ol>
 *   <li><b>Submit</b>: staged parser rows below a confidence cut-off go to {@link CharacterNameModel}
 *       as one Message Batch (half price, results within 24 hours). Nothing else changes.</li>
 *   <li><b>Collect</b>: once the batch has ended, its answers are stored in character_model_decisions.</li>
 *   <li><b>Stage</b>: decisions are written into character_reparse_rows (source MODEL), keeping the
 *       recorded old values; {@code POST /character-name-reparse/apply} then writes them.</li>
 * </ol>
 *
 * {@link #pilot} decides a list of strings directly without storing anything, to measure the model on
 * labelled credits before paying for a full batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterNameModelService {

    public static final double DEFAULT_MAX_CONFIDENCE = CharacterNameReparseService.LOW_CONFIDENCE;
    /** What a model decision is recorded with, so it no longer counts as a low-confidence parse. */
    static final double MODEL_CONFIDENCE = 0.8;
    private static final int PILOT_LIMIT = 2000;
    private static final int PILOT_THREADS = 4;
    private static final int CHUNK = 500;
    private static final int SAMPLE = 40;
    /** Batch API prices for the model, per million tokens (half the standard rate). */
    private static final double INPUT_PER_MTOK = 0.05;
    private static final double OUTPUT_PER_MTOK = 0.25;
    /** Measured on the first batch: $0.86 for 72,777 credits. */
    private static final double COST_PER_ITEM_USD = 0.86 / 72_777;
    private final CharacterNameParserV2 parser = new CharacterNameParserV2();

    private final JdbcTemplate jdbc;
    private final CharacterNameModel model;
    private final CharacterNameReparseService reparseService;

    public static class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message) {
            super(message);
        }
    }

    private void ensureConfigured() {
        if (!model.configured()) {
            throw new ModelUnavailableException("Neither ANTHROPIC_API_KEY nor CLAUDE_API_KEY is set on this service.");
        }
    }

    // ---------------------------------------------------------------- pilot

    public record PilotDecision(String fullName, String firstName, String error) {}

    public record PilotResult(String model, int credits, int requests, List<PilotDecision> decisions,
                              long inputTokens, long outputTokens, double estimatedCostUsd) {}

    /** Decides the given raw credits now (standard price), with the same context a batch would send. */
    public PilotResult pilot(List<String> fullNames) {
        ensureConfigured();
        List<String> names = fullNames.stream().filter(Objects::nonNull).map(String::trim)
            .filter(n -> !n.isEmpty()).distinct().limit(PILOT_LIMIT).toList();
        Map<String, Item> context = contextByFullName(names);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Item c = context.get(names.get(i));
            items.add(c == null ? new Item(i, names.get(i), null, null, null, null)
                : new Item(i, names.get(i), c.movieTitle(), c.year(), c.language(), c.actorName()));
        }
        List<List<Item>> requests = chunks(items, CharacterNameModel.ITEMS_PER_REQUEST);
        ExecutorService pool = Executors.newFixedThreadPool(PILOT_THREADS);
        try {
            List<Future<Answer>> futures = requests.stream().map(r -> pool.submit(() -> model.classify(r))).toList();
            Map<Long, PilotDecision> byId = new HashMap<>();
            long in = 0;
            long out = 0;
            for (int r = 0; r < requests.size(); r++) {
                Answer answer;
                try {
                    answer = futures.get(r).get();
                } catch (ExecutionException e) {
                    answer = new Answer(List.of(), null, String.valueOf(e.getCause()));
                }
                if (answer.usage() != null) {
                    in += answer.usage().inputTokens();
                    out += answer.usage().outputTokens();
                }
                for (Decision d : answer.decisions()) {
                    byId.put(d.id(), new PilotDecision(names.get((int) d.id()), d.firstName(), null));
                }
                for (Item item : requests.get(r)) {
                    byId.putIfAbsent(item.id(), new PilotDecision(item.fullName(), null,
                        answer.error() != null ? answer.error() : "no answer for this credit"));
                }
            }
            List<PilotDecision> decisions = items.stream().map(i -> byId.get(i.id())).toList();
            // The pilot runs at the standard price, twice the batch rate.
            double cost = 2 * (in * INPUT_PER_MTOK + out * OUTPUT_PER_MTOK) / 1e6;
            return new PilotResult(CharacterNameModel.MODEL, names.size(), requests.size(), decisions, in, out, cost);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("pilot interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- submit

    public record BatchInfo(String batchId, String model, int requests, int items, String status,
                            LocalDateTime submittedAt, LocalDateTime collectedAt, int failedRequests,
                            long inputTokens, long outputTokens, long cacheReadTokens, double estimatedCostUsd) {}

    public record SubmitResult(boolean preview, long candidates, long sent, Map<String, Long> skippedCertain,
                               double estimatedCostUsd, BatchInfo batch) {}

    /** Submits without skipping certain parses (the original low-confidence pass). */
    public BatchInfo submit(double maxConfidence, int limit) {
        return submit(maxConfidence, limit, false, false).batch();
    }

    /**
     * Sends every staged parser row below {@code maxConfidence} that has no decision yet (and is not
     * manually verified), at most {@code limit} of them, as one batch. With {@code skipCertain}, rows a
     * {@link CertainParses} rule covers are left to the parser. With {@code preview}, only reports what
     * would be sent. Refuses while another batch is open.
     */
    public SubmitResult submit(double maxConfidence, int limit, boolean skipCertain, boolean preview) {
        if (!preview) {
            ensureConfigured();
        }
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM character_model_batches WHERE collected_at IS NULL",
            Integer.class);
        if (!preview && open != null && open > 0) {
            throw new IllegalStateException("A batch is still open; collect it first.");
        }
        List<Object[]> candidates = jdbc.query("""
            SELECT s.character_id, s.full_name, s.new_first_name
            FROM character_reparse_rows s
            JOIN characters ch ON ch.id = s.character_id
            LEFT JOIN character_model_decisions d ON d.character_id = s.character_id
            WHERE s.source = 'PARSER' AND s.confidence < ? AND ch.manually_verified = FALSE AND d.character_id IS NULL
            ORDER BY s.character_id
            LIMIT ?
            """, (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)},
            maxConfidence, skipCertain ? Integer.MAX_VALUE : Math.max(1, limit));
        long candidateCount = candidates.size();
        Map<String, Long> skipped = new TreeMap<>();
        if (skipCertain) {
            List<Object[]> uncertain = new ArrayList<>();
            for (Object[] c : candidates) {
                String rule = CertainParses.rule(parser.parse((String) c[1]));
                if (rule == null) {
                    if (uncertain.size() < Math.max(1, limit)) {
                        uncertain.add(c);
                    }
                } else {
                    skipped.merge(rule, 1L, Long::sum);
                }
            }
            candidates = uncertain;
        }
        double estimate = candidates.size() * COST_PER_ITEM_USD;
        if (preview) {
            return new SubmitResult(true, candidateCount, candidates.size(), skipped, estimate, null);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No staged rows are waiting for a decision.");
        }
        Map<Long, Item> context = contextByCharacterId(candidates.stream().map(c -> (Long) c[0]).toList());
        List<Item> items = candidates.stream().map(c -> {
            Item ctx = context.get((Long) c[0]);
            return ctx != null ? ctx : new Item((Long) c[0], (String) c[1], null, null, null, null);
        }).toList();
        Map<String, List<Item>> requests = new LinkedHashMap<>();
        List<List<Item>> chunks = chunks(items, CharacterNameModel.ITEMS_PER_REQUEST);
        for (int i = 0; i < chunks.size(); i++) {
            requests.put(String.format("r%05d", i), chunks.get(i));
        }

        MessageBatch batch = model.submit(requests);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO character_model_batches (batch_id, model, requests, items, status, submitted_at, "
            + "failed_requests, input_tokens, output_tokens, cache_read_tokens) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0)",
            batch.id(), CharacterNameModel.MODEL, requests.size(), items.size(),
            batch.processingStatus().toString(), now);
        Map<Long, String> parserFirst = new HashMap<>();
        candidates.forEach(c -> parserFirst.put((Long) c[0], (String) c[2]));
        List<Object[]> rows = new ArrayList<>();
        requests.forEach((customId, chunk) -> chunk.forEach(item -> rows.add(new Object[] {item.id(), item.fullName(),
            batch.id(), customId, parserFirst.get(item.id()), CharacterNameModel.MODEL, "PENDING", now})));
        for (int i = 0; i < rows.size(); i += CHUNK) {
            jdbc.batchUpdate("INSERT INTO character_model_decisions (character_id, full_name, batch_id, custom_id, "
                + "parser_first_name, model, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                rows.subList(i, Math.min(rows.size(), i + CHUNK)));
        }
        log.info("Submitted model batch {}: {} credits in {} requests", batch.id(), items.size(), requests.size());
        return new SubmitResult(false, candidateCount, items.size(), skipped, estimate, batch(batch.id()).orElseThrow());
    }

    // ---------------------------------------------------------------- collect

    /** Refreshes an open batch's status and, once it has ended, stores its answers. */
    public BatchInfo collect(String batchId) {
        ensureConfigured();
        BatchInfo info = batch(batchId).orElseThrow(() -> new NoSuchElementException("Unknown batch " + batchId));
        if (info.collectedAt() != null) {
            return info;
        }
        MessageBatch batch = model.retrieve(batchId);
        String status = batch.processingStatus().toString();
        jdbc.update("UPDATE character_model_batches SET status = ? WHERE batch_id = ?", status, batchId);
        if (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
            return batch(batchId).orElseThrow();
        }

        Map<String, List<Item>> itemsByRequest = new HashMap<>();
        jdbc.query("SELECT character_id, full_name, custom_id FROM character_model_decisions WHERE batch_id = ? "
            + "AND status = 'PENDING'", rs -> {
            itemsByRequest.computeIfAbsent(rs.getString(3), k -> new ArrayList<>())
                .add(new Item(rs.getLong(1), rs.getString(2), null, null, null, null));
        }, batchId);
        long[] totals = new long[4];
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        model.results(batchId, itemsByRequest, entry -> {
            Answer answer = entry.getValue();
            if (answer.usage() != null) {
                totals[0] += answer.usage().inputTokens();
                totals[1] += answer.usage().outputTokens();
                totals[2] += answer.usage().cacheReadTokens();
            }
            if (answer.error() != null) {
                totals[3]++;
            }
            List<Object[]> done = new ArrayList<>();
            Set<Long> answered = new HashSet<>();
            for (Decision d : answer.decisions()) {
                answered.add(d.id());
                done.add(new Object[] {d.firstName(), now, d.id()});
            }
            List<Object[]> failed = new ArrayList<>();
            for (Item item : itemsByRequest.getOrDefault(entry.getKey(), List.of())) {
                if (!answered.contains(item.id())) {
                    String error = answer.error() != null ? answer.error() : "no usable answer";
                    failed.add(new Object[] {error.length() > 200 ? error.substring(0, 200) : error, now, item.id()});
                }
            }
            if (!done.isEmpty()) {
                jdbc.batchUpdate("UPDATE character_model_decisions SET model_first_name = ?, status = 'DONE', "
                    + "decided_at = ? WHERE character_id = ?", done);
            }
            if (!failed.isEmpty()) {
                jdbc.batchUpdate("UPDATE character_model_decisions SET error = ?, status = 'FAILED', decided_at = ? "
                    + "WHERE character_id = ?", failed);
            }
        });
        jdbc.update("UPDATE character_model_batches SET collected_at = ?, input_tokens = ?, output_tokens = ?, "
            + "cache_read_tokens = ?, failed_requests = ? WHERE batch_id = ?",
            now, totals[0], totals[1], totals[2], (int) totals[3], batchId);
        log.info("Collected model batch {}: {} input, {} output tokens, {} failed requests",
            batchId, totals[0], totals[1], totals[3]);
        return batch(batchId).orElseThrow();
    }

    // ---------------------------------------------------------------- stage

    public record StageResult(long decisions, long staged, long unchanged, long prefixFallbacks) {}

    /**
     * Writes collected decisions that differ from the staged parse into the re-parse staging table. A
     * decision is marked staged either way, so it is not looked at again; {@code recheck} goes over the
     * staged ones too, so a change to the rules below reaches decisions that were already applied.
     *
     * <p>An answer that is an age/kin prefix or a title ("Young Griff" -> Young, "Lady Bayford" -> Lady)
     * is not used: the parser's answer is staged instead.
     */
    public StageResult stage(boolean recheck) {
        List<Object[]> decided = jdbc.query("""
            SELECT d.character_id, d.full_name, d.model_first_name, s.new_first_name, s.new_last_name, s.new_name_type
            FROM character_model_decisions d
            JOIN character_reparse_rows s ON s.character_id = d.character_id
            WHERE d.status = 'DONE'""" + (recheck ? "" : " AND d.staged_at IS NULL"),
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6)});
        Map<Long, Planned> plans = new HashMap<>();
        long fallbacks = 0;
        for (Object[] d : decided) {
            String modelFirst = (String) d[2];
            Planned plan;
            if (isPrefixOrTitle(modelFirst)) {
                plan = reparseService.parserPlan((String) d[1]);
                fallbacks++;
            } else {
                plan = planFor((String) d[1], modelFirst, (String) d[3], (String) d[4], (String) d[5]);
            }
            if (plan != null) {
                plans.put((Long) d[0], plan);
            }
        }
        long staged = reparseService.stageDecided(plans);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> ids = decided.stream().map(d -> new Object[] {now, d[0]}).toList();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            jdbc.batchUpdate("UPDATE character_model_decisions SET staged_at = ? WHERE character_id = ?",
                ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        }
        return new StageResult(decided.size(), staged, decided.size() - plans.size(), fallbacks);
    }

    /** A word the parser strips in front of a given name: "Young", "Lady", "Grandma", "King", "Captain". */
    static boolean isPrefixOrTitle(String firstName) {
        if (firstName == null) {
            return false;
        }
        String folded = CharacterNameLexicon.fold(firstName);
        CharacterNameLexicon lexicon = CharacterNameLexicon.loadDefault();
        return lexicon.isAgeOrKinPrefix(folded) || lexicon.isGivenTitle(folded) || lexicon.isSurnameTitle(folded);
    }

    /** The stored name parts for a model's answer, or null when it agrees with the staged parse. */
    static Planned planFor(String fullName, String modelFirst, String stagedFirst, String stagedLast, String stagedType) {
        if (modelFirst == null) {
            if (stagedFirst == null) {
                return null;
            }
            return new Planned(null, null, NameType.ROLE_DESCRIPTION.name(), MODEL_CONFIDENCE,
                CharacterNameModel.MODEL, Source.MODEL);
        }
        if (modelFirst.equals(stagedFirst)) {
            return null;
        }
        String last = CharacterNameReparseService.surnameAfter(fullName, modelFirst);
        String type = last == null && fullName.strip().equalsIgnoreCase(modelFirst)
            ? NameType.SINGLE_NAME.name() : NameType.STANDARD.name();
        return new Planned(modelFirst, last, type, MODEL_CONFIDENCE, CharacterNameModel.MODEL, Source.MODEL);
    }

    // ---------------------------------------------------------------- summary

    public record Disagreement(long characterId, String fullName, String parserFirstName, String modelFirstName) {}

    public record Summary(boolean configured, Map<String, Long> byStatus, long agree, long nameToNull,
                          long nullToName, long otherName, List<Disagreement> samples, List<BatchInfo> batches) {}

    public Summary summary() {
        Map<String, Long> byStatus = new TreeMap<>();
        jdbc.query("SELECT status, COUNT(*) FROM character_model_decisions GROUP BY status",
            rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        long[] c = new long[4];
        jdbc.query("SELECT parser_first_name, model_first_name FROM character_model_decisions WHERE status = 'DONE'",
            rs -> {
                String p = rs.getString(1);
                String m = rs.getString(2);
                if (Objects.equals(p, m)) {
                    c[0]++;
                } else if (m == null) {
                    c[1]++;
                } else if (p == null) {
                    c[2]++;
                } else {
                    c[3]++;
                }
            });
        List<Disagreement> samples = jdbc.query("""
            SELECT character_id, full_name, parser_first_name, model_first_name FROM character_model_decisions
            WHERE status = 'DONE' AND ((parser_first_name IS NULL AND model_first_name IS NOT NULL)
                OR (parser_first_name IS NOT NULL AND model_first_name IS NULL)
                OR parser_first_name <> model_first_name)
            ORDER BY RANDOM() LIMIT ?
            """, (rs, i) -> new Disagreement(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), SAMPLE);
        List<BatchInfo> batches = jdbc.query("SELECT batch_id FROM character_model_batches ORDER BY submitted_at DESC",
            (rs, i) -> rs.getString(1)).stream().map(id -> batch(id).orElseThrow()).toList();
        return new Summary(model.configured(), byStatus, c[0], c[1], c[2], c[3], samples, batches);
    }

    private Optional<BatchInfo> batch(String batchId) {
        return jdbc.query("SELECT * FROM character_model_batches WHERE batch_id = ?", (rs, i) -> {
            long in = rs.getLong("input_tokens");
            long out = rs.getLong("output_tokens");
            Timestamp collected = rs.getTimestamp("collected_at");
            return new BatchInfo(rs.getString("batch_id"), rs.getString("model"), rs.getInt("requests"),
                rs.getInt("items"), rs.getString("status"), rs.getTimestamp("submitted_at").toLocalDateTime(),
                collected == null ? null : collected.toLocalDateTime(), rs.getInt("failed_requests"), in, out,
                rs.getLong("cache_read_tokens"), (in * INPUT_PER_MTOK + out * OUTPUT_PER_MTOK) / 1e6);
        }, batchId).stream().findFirst();
    }

    // ---------------------------------------------------------------- context

    /** Each character's highest-revenue credit: the movie and the actor who played it. */
    private Map<Long, Item> contextByCharacterId(List<Long> ids) {
        Map<Long, Item> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + CHUNK));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query("""
                SELECT x.id, x.full_name, x.title, x.release_date, x.original_language, x.actor FROM (
                    SELECT ch.id AS id, ch.full_name AS full_name, m.title AS title, m.release_date AS release_date,
                           m.original_language AS original_language, p.full_name AS actor,
                           ROW_NUMBER() OVER (PARTITION BY ch.id ORDER BY COALESCE(m.revenue, 0) DESC, m.id) AS rn
                    FROM characters ch
                    LEFT JOIN credits cr ON cr.character_id = ch.id
                    LEFT JOIN movies m ON m.id = cr.movie_id
                    LEFT JOIN people p ON p.id = cr.person_id
                    WHERE ch.id IN (%s)
                ) x WHERE x.rn = 1
                """.formatted(in), rs -> {
                Date released = rs.getDate("release_date");
                result.put(rs.getLong("id"), new Item(rs.getLong("id"), rs.getString("full_name"), rs.getString("title"),
                    released == null ? null : released.toLocalDate().getYear(), rs.getString("original_language"),
                    rs.getString("actor")));
            }, chunk.toArray());
        }
        return result;
    }

    private Map<String, Item> contextByFullName(List<String> names) {
        Map<Long, String> idByName = new HashMap<>();
        Map<String, Long> firstId = new HashMap<>();
        for (int i = 0; i < names.size(); i += CHUNK) {
            List<String> chunk = names.subList(i, Math.min(names.size(), i + CHUNK));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query("SELECT MIN(id), full_name FROM characters WHERE full_name IN (" + in + ") GROUP BY full_name",
                rs -> { firstId.put(rs.getString(2), rs.getLong(1)); }, chunk.toArray());
        }
        firstId.forEach((name, id) -> idByName.put(id, name));
        Map<String, Item> result = new HashMap<>();
        contextByCharacterId(new ArrayList<>(idByName.keySet())).forEach((id, item) -> result.put(idByName.get(id), item));
        return result;
    }

    private static <T> List<List<T>> chunks(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return out;
    }
}

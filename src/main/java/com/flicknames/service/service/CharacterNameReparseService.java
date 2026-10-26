package com.flicknames.service.service;

import com.flicknames.service.entity.CharacterNameLabel;
import com.flicknames.service.entity.CharacterReparseRow.Source;
import com.flicknames.service.entity.NameTypeConverter;
import com.flicknames.service.entity.ScreenCharacter;
import com.flicknames.service.entity.ScreenCharacter.NameType;
import com.flicknames.service.service.CharacterNameLabelService.LabelResult;
import com.flicknames.service.service.CharacterNameLabelService.NameParts;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import com.flicknames.service.util.charactername.CharacterNameParseResult;
import com.flicknames.service.util.charactername.CharacterNameParserV2;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Re-parses every stored character name with {@link CharacterNameParserV2}, in three explicit steps:
 *
 * <ol>
 *   <li><b>Dry run</b>: parses all characters into character_reparse_rows, recording each row's current
 *       values next to the new ones. The characters table is not touched.</li>
 *   <li><b>Apply</b>: copies the staged values onto characters, but only where a row still holds the
 *       values the dry run saw (anything edited since is skipped and counted). Decisive labels are then
 *       applied through {@link CharacterNameLabelService#apply}, which also marks those rows verified.</li>
 *   <li><b>Revert</b>: restores the recorded old values on applied rows that still hold the new ones.</li>
 * </ol>
 *
 * Manually verified characters are left out entirely. Strings with a decisive human label are staged
 * with the label's answer instead of the parser's. Steps run one at a time on a background thread;
 * {@link #status()} reports progress. The raw TMDB string is kept in characters.full_name, so a later
 * parser version can be re-run the same way without re-collecting anything.
 */
@Service
@Slf4j
public class CharacterNameReparseService {

    /** Below this the parser is unsure; such strings are the labeling (or LLM) candidates. */
    public static final double LOW_CONFIDENCE = 0.7;
    public static final String LABEL_VERSION = "label";
    private static final int CHUNK = 1000;
    private static final int MAX_TRANSITIONS = 50;
    private static final int EXAMPLES_PER_TRANSITION = 3;
    private static final int MAX_STALE_SAMPLES = 20;
    private static final int MAX_LAST_NAME = 255;
    private static final NameTypeConverter NAME_TYPES = new NameTypeConverter();

    private static final String ROW_COLUMNS = """
        character_id, full_name, old_first_name, old_last_name, old_name_type, old_parser_version,
        old_parse_confidence, new_first_name, new_last_name, new_name_type, confidence, parser_version,
        source, changed\
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CharacterNameLabelService labelService;
    private final CharacterNameLabelQueueService queueService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "character-reparse");
        t.setDaemon(true);
        return t;
    });
    private volatile CharacterNameParserV2 parser;
    private volatile Job job;
    /** The last report: it aggregates every credit, so repeat requests reuse it until a step runs. */
    private volatile CachedReport cachedReport;

    private record CachedReport(int top, Integer fromYear, Integer toYear, Instant computedAt, Report report) {}

    private static final Duration REPORT_TTL = Duration.ofMinutes(10);

    public CharacterNameReparseService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                       CharacterNameLabelService labelService,
                                       CharacterNameLabelQueueService queueService) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.labelService = labelService;
        this.queueService = queueService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- jobs

    public enum JobKind { DRY_RUN, APPLY, REVERT }

    public enum JobState { RUNNING, SUCCEEDED, FAILED }

    public record JobStatus(JobKind kind, JobState state, long processed, long total, Instant startedAt,
                            Instant finishedAt, String error, Object result) {}

    /** Thrown when a step cannot start now (another is running, or it would lose the backup). */
    public static class ReparseConflictException extends RuntimeException {
        public ReparseConflictException(String message) {
            super(message);
        }
    }

    private static final class Job {
        final JobKind kind;
        final Instant startedAt = Instant.now();
        final AtomicLong processed = new AtomicLong();
        volatile long total;
        volatile JobState state = JobState.RUNNING;
        volatile Instant finishedAt;
        volatile String error;
        volatile Object result;

        Job(JobKind kind) {
            this.kind = kind;
        }

        JobStatus snapshot() {
            return new JobStatus(kind, state, processed.get(), total, startedAt, finishedAt, error, result);
        }
    }

    /** The current or most recent step since the service started, or empty. */
    public Optional<JobStatus> status() {
        Job current = job;
        return current == null ? Optional.empty() : Optional.of(current.snapshot());
    }

    /**
     * Starts a dry run in the background. Refuses while rows of an earlier run are applied, because a
     * new dry run replaces the staged rows and with them the backup; pass {@code discardAppliedBackup}
     * to accept that.
     */
    public synchronized JobStatus startDryRun(boolean discardAppliedBackup) {
        return startDryRun(discardAppliedBackup, false);
    }

    /**
     * With {@code onlyNew}, keeps the staged rows (and so the backup) and stages only characters that are
     * not staged yet, such as rows the collector added after the last dry run; applying then writes just those.
     */
    public synchronized JobStatus startDryRun(boolean discardAppliedBackup, boolean onlyNew) {
        return startDryRun(discardAppliedBackup, onlyNew, false);
    }

    /**
     * With {@code refresh}, also re-plans already staged rows with the current parser while keeping their
     * recorded old values, so a fixed parser can be applied over an applied run and a revert still
     * restores what was there before the first apply. Applied rows edited since their apply are left alone.
     */
    public synchronized JobStatus startDryRun(boolean discardAppliedBackup, boolean onlyNew, boolean refresh) {
        ensureIdle();
        if (!onlyNew && !refresh && !discardAppliedBackup && appliedRows() > 0) {
            throw new ReparseConflictException("The staged run is applied and its rows are the backup. Revert it "
                + "first, pass refresh=true to re-plan the staged rows and keep the backup, pass onlyNew=true to "
                + "stage just the characters added since, or pass discardAppliedBackup=true to start over.");
        }
        return start(JobKind.DRY_RUN, () -> dryRun(onlyNew || refresh, refresh));
    }

    public synchronized JobStatus startApply() {
        ensureIdle();
        if (stagedRows() == 0) {
            throw new ReparseConflictException("Nothing is staged; run a dry run first.");
        }
        return start(JobKind.APPLY, this::apply);
    }

    public synchronized JobStatus startRevert() {
        ensureIdle();
        if (appliedRows() == 0) {
            throw new ReparseConflictException("No staged rows are applied; there is nothing to revert.");
        }
        return start(JobKind.REVERT, this::revert);
    }

    private void ensureIdle() {
        Job current = job;
        if (current != null && current.state == JobState.RUNNING) {
            throw new ReparseConflictException("A " + current.kind + " is already running.");
        }
    }

    private JobStatus start(JobKind kind, Supplier<Object> work) {
        Job started = new Job(kind);
        job = started;
        cachedReport = null;
        executor.submit(() -> {
            try {
                started.result = work.get();
                started.state = JobState.SUCCEEDED;
            } catch (RuntimeException e) {
                log.error("Character re-parse {} failed", kind, e);
                started.error = e.getMessage();
                started.state = JobState.FAILED;
            } finally {
                started.finishedAt = Instant.now();
                cachedReport = null;
            }
        });
        return started.snapshot();
    }

    private void progress(long processed) {
        Job current = job;
        if (current != null) {
            current.processed.set(processed);
        }
    }

    private void total(long total) {
        Job current = job;
        if (current != null) {
            current.total = total;
        }
    }

    // ---------------------------------------------------------------- dry run

    public record DryRunResult(String parserVersion, long characters, long staged, long skippedVerified,
                               long fromLabels, long changed, long lowConfidence, long replanned) {}

    private record Stored(long id, String fullName, String firstName, String lastName, String nameType,
                          String parserVersion, Double parseConfidence, boolean verified) {}

    record Planned(String firstName, String lastName, String nameType, double confidence, String parserVersion,
                   Source source) {}

    /** Stages the v2 (or label) result for every character that is not manually verified. */
    public DryRunResult dryRun() {
        return dryRun(false);
    }

    /** @param onlyNew keep the staged rows and stage only characters not staged yet */
    public DryRunResult dryRun(boolean onlyNew) {
        return dryRun(onlyNew, false);
    }

    private record StagedPlan(String oldFirstName, String oldLastName, String oldNameType, String newFirstName,
                              String newLastName, String newNameType, boolean applied, String appliedFirstName,
                              String appliedLastName, String appliedNameType, String source) {
        /** What the last apply wrote: recorded since refresh existed, else the staged new values. */
        List<String> written() {
            return appliedNameType != null ? Arrays.asList(appliedFirstName, appliedLastName, appliedNameType)
                : Arrays.asList(newFirstName, newLastName, newNameType);
        }
    }

    /**
     * @param onlyNew keep the staged rows and stage characters not staged yet
     * @param refresh also re-plan staged rows with the current parser, keeping their recorded old values
     */
    public DryRunResult dryRun(boolean onlyNew, boolean refresh) {
        Map<String, LabelResult> labels = labelService.decisiveLabels();
        long characters = count("SELECT COUNT(*) FROM characters");
        total(characters);
        if (!onlyNew) {
            jdbc.update("DELETE FROM character_reparse_rows");
        }

        long processed = 0;
        long staged = 0;
        long verified = 0;
        long fromLabels = 0;
        long changed = 0;
        long lowConfidence = 0;
        long replanned = 0;
        long lastId = 0;
        while (true) {
            List<Stored> rows = jdbc.query("""
                SELECT id, full_name, first_name, last_name, name_type, parser_version, parse_confidence,
                       manually_verified
                FROM characters WHERE id > ? ORDER BY id LIMIT ?
                """, (rs, i) -> new Stored(rs.getLong("id"), rs.getString("full_name"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("name_type"), rs.getString("parser_version"),
                rs.getObject("parse_confidence", Double.class), rs.getBoolean("manually_verified")),
                lastId, CHUNK);
            if (rows.isEmpty()) {
                break;
            }
            Map<Long, StagedPlan> alreadyStaged = new HashMap<>();
            if (onlyNew) {
                alreadyStaged.putAll(stagedPlans("character_id BETWEEN ? AND ?", rows.get(0).id(), rows.get(rows.size() - 1).id()));
            }
            List<Object[]> batch = new ArrayList<>(rows.size());
            List<Object[]> replans = new ArrayList<>();
            for (Stored row : rows) {
                StagedPlan existing = alreadyStaged.get(row.id());
                if (existing != null) {
                    // A model's decision for a hard credit stays until a label or a new model pass replaces it.
                    if (refresh && !row.verified() && !Source.MODEL.name().equals(existing.source())) {
                        Object[] update = replan(row, existing, plan(row, labels.get(row.fullName())));
                        if (update != null) {
                            replans.add(update);
                            replanned++;
                        }
                    }
                    continue;
                }
                if (row.verified()) {
                    verified++;
                    continue;
                }
                Planned plan = plan(row, labels.get(row.fullName()));
                boolean differs = !Objects.equals(row.firstName(), plan.firstName())
                    || !Objects.equals(row.lastName(), plan.lastName())
                    || !Objects.equals(row.nameType(), plan.nameType());
                staged++;
                if (differs) {
                    changed++;
                }
                if (plan.source() == Source.LABEL) {
                    fromLabels++;
                } else if (plan.confidence() < LOW_CONFIDENCE) {
                    lowConfidence++;
                }
                batch.add(new Object[] {row.id(), row.fullName(), row.firstName(), row.lastName(), row.nameType(),
                    row.parserVersion(), row.parseConfidence(), plan.firstName(), plan.lastName(), plan.nameType(),
                    plan.confidence(), plan.parserVersion(), plan.source().name(), differs});
            }
            if (!batch.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO character_reparse_rows (" + ROW_COLUMNS + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch, new int[] {
                        Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.DOUBLE, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.DOUBLE, Types.VARCHAR,
                        Types.VARCHAR, Types.BOOLEAN});
            }
            writeReplans(replans);
            processed += rows.size();
            progress(processed);
            lastId = rows.get(rows.size() - 1).id();
        }

        DryRunResult result = new DryRunResult(CharacterNameParserV2.VERSION, processed, staged, verified, fromLabels,
            changed, lowConfidence, replanned);
        log.info("Character re-parse dry run: {}", result);
        return result;
    }

    private Map<Long, StagedPlan> stagedPlans(String where, Object... args) {
        Map<Long, StagedPlan> plans = new HashMap<>();
        jdbc.query("""
            SELECT character_id, old_first_name, old_last_name, old_name_type, new_first_name, new_last_name,
                   new_name_type, applied_at, applied_first_name, applied_last_name, applied_name_type, source
            FROM character_reparse_rows WHERE\s""" + where, rs -> {
            plans.put(rs.getLong("character_id"), new StagedPlan(rs.getString("old_first_name"),
                rs.getString("old_last_name"), rs.getString("old_name_type"), rs.getString("new_first_name"),
                rs.getString("new_last_name"), rs.getString("new_name_type"), rs.getTimestamp("applied_at") != null,
                rs.getString("applied_first_name"), rs.getString("applied_last_name"),
                rs.getString("applied_name_type"), rs.getString("source")));
        }, args);
        return plans;
    }

    private void writeReplans(List<Object[]> replans) {
        if (replans.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
            UPDATE character_reparse_rows
            SET new_first_name = ?, new_last_name = ?, new_name_type = ?, confidence = ?, parser_version = ?,
                source = ?, changed = ?, applied_first_name = ?, applied_last_name = ?, applied_name_type = ?,
                applied_at = NULL
            WHERE character_id = ?
            """, replans, new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.DOUBLE, Types.VARCHAR,
                Types.VARCHAR, Types.BOOLEAN, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT});
    }

    /**
     * Stages name parts decided outside the parser (a model pass) for characters that are already staged,
     * with the same guards as a refresh: the recorded old values stay, applied rows edited since their
     * apply and manually verified rows are skipped. Apply then writes them. Returns how many were staged.
     */
    public long stageDecided(Map<Long, Planned> plans) {
        List<Long> ids = new ArrayList<>(plans.keySet());
        Collections.sort(ids);
        long staged = 0;
        for (int i = 0; i < ids.size(); i += CHUNK) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + CHUNK));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Map<Long, StagedPlan> existing = stagedPlans("character_id IN (" + in + ")", chunk.toArray());
            List<Object[]> replans = new ArrayList<>();
            jdbc.query("SELECT id, full_name, first_name, last_name, name_type, parser_version, parse_confidence, "
                + "manually_verified FROM characters WHERE id IN (" + in + ")", rs -> {
                Stored row = new Stored(rs.getLong("id"), rs.getString("full_name"), rs.getString("first_name"),
                    rs.getString("last_name"), rs.getString("name_type"), rs.getString("parser_version"),
                    rs.getObject("parse_confidence", Double.class), rs.getBoolean("manually_verified"));
                StagedPlan plan = existing.get(row.id());
                if (plan != null && !row.verified()) {
                    Object[] update = replan(row, plan, plans.get(row.id()));
                    if (update != null) {
                        replans.add(update);
                    }
                }
            }, chunk.toArray());
            writeReplans(replans);
            staged += replans.size();
        }
        cachedReport = null;
        return staged;
    }

    /**
     * The staging update that re-plans an already staged row, or null when nothing needs doing: the plan
     * equals what the row holds, or the row was applied and has been edited since.
     */
    private static Object[] replan(Stored row, StagedPlan existing, Planned plan) {
        List<String> current = Arrays.asList(row.firstName(), row.lastName(), row.nameType());
        List<String> planned = Arrays.asList(plan.firstName(), plan.lastName(), plan.nameType());
        List<String> written = existing.written();
        if (existing.applied() && !current.equals(written)) {
            return null;   // edited since the apply: leave it
        }
        List<String> staged = Arrays.asList(existing.newFirstName(), existing.newLastName(), existing.newNameType());
        if (planned.equals(existing.applied() ? written : staged)) {
            return null;
        }
        boolean changed = !planned.equals(Arrays.asList(existing.oldFirstName(), existing.oldLastName(), existing.oldNameType()));
        // An applied row keeps what it was written as, so the next apply and a revert recognise it.
        List<String> keep = existing.applied() ? written
            : Arrays.asList(existing.appliedFirstName(), existing.appliedLastName(), existing.appliedNameType());
        return new Object[] {plan.firstName(), plan.lastName(), plan.nameType(), plan.confidence(), plan.parserVersion(),
            plan.source().name(), changed, keep.get(0), keep.get(1), keep.get(2), row.id()};
    }

    private Planned plan(Stored row, LabelResult label) {
        if (label != null) {
            ScreenCharacter character = ScreenCharacter.builder().fullName(row.fullName()).firstName(row.firstName())
                .lastName(row.lastName()).nameType(NAME_TYPES.convertToEntityAttribute(row.nameType())).build();
            CharacterNameLabel decision = CharacterNameLabel.builder().fullName(row.fullName())
                .verdict(label.verdict()).firstName(label.firstName()).build();
            NameParts parts = CharacterNameLabelService.planFor(character, decision);
            return new Planned(parts.firstName(), parts.lastName(), parts.nameType().name(), 1.0, LABEL_VERSION,
                Source.LABEL);
        }
        CharacterNameParseResult result = parser().parse(row.fullName());
        return new Planned(result.firstName(), lastName(result), storedType(result).name(), result.confidence(),
            result.parserVersion(), Source.PARSER);
    }

    private CharacterNameParserV2 parser() {
        CharacterNameParserV2 p = parser;
        if (p == null) {
            synchronized (this) {
                if (parser == null) {
                    parser = new CharacterNameParserV2(); // loads the lexicons once
                }
                p = parser;
            }
        }
        return p;
    }

    /** The stored (v1) name type for a v2 result. */
    static NameType storedType(CharacterNameParseResult result) {
        return switch (result.nameType()) {
            case PERSONAL_NAME, AGE_OR_KIN_VARIANT, TITLED_NAME, NAME_WITH_DESCRIPTION -> NameType.STANDARD;
            case SINGLE_NAME -> NameType.SINGLE_NAME;
            case TITLE_SURNAME -> NameType.TITLE_SURNAME;
            case NUMBERED_ROLE -> NameType.NUMBERED_ROLE;
            case SELF, RELATIONAL_ROLE, ROLE_DESCRIPTION -> NameType.ROLE_DESCRIPTION;
            case EMPTY -> NameType.UNKNOWN;
        };
    }

    private static final Pattern QUOTED = Pattern.compile("(^|\\s)[\"“‘'][^\"”’']+[\"”’'](?=\\s|$)");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\"“‘'(\\[]+|[\"”’')\\].,;:!?]+$");

    /**
     * The surname for a v2 result, from the chosen segment: what follows the first name for personal,
     * titled and age/kin names ("Dr. Roshan Manik" -> Manik, "Young Nancy" -> null), the last word for
     * title + surname ("Officer Daniels" -> Daniels), otherwise null. Stops at a dash or comma, which
     * start a description.
     */
    static String lastName(CharacterNameParseResult result) {
        if (result.segment() == null || result.nameType() == null) {
            return null;
        }
        List<String> words = words(result.segment());
        String last = switch (result.nameType()) {
            case TITLE_SURNAME -> words.isEmpty() ? null : words.get(words.size() - 1);
            case PERSONAL_NAME, TITLED_NAME, AGE_OR_KIN_VARIANT -> after(words, result.firstName());
            default -> null;
        };
        return capped(last);
    }

    /**
     * The surname behind a first name decided elsewhere (a model or a label) in a raw credit: the words
     * after it in the role segment that contains it, up to a dash, comma or annotation.
     */
    static String surnameAfter(String fullName, String firstName) {
        if (fullName == null || firstName == null) {
            return null;
        }
        for (String segment : fullName.split("\\s*[/|;]\\s*")) {
            List<String> words = words(segment);
            if (containsName(words, firstName)) {
                return capped(after(words, firstName));
            }
        }
        return null;
    }

    private static boolean containsName(List<String> words, String firstName) {
        String[] first = firstName.trim().split("\\s+");
        for (int i = 0; i + first.length <= words.size(); i++) {
            boolean match = true;
            for (int j = 0; j < first.length && match; j++) {
                match = words.get(i + j).equalsIgnoreCase(first[j]);
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String segment) {
        String[] raw = QUOTED.matcher(segment).replaceAll(" ").trim().split("\\s+");
        List<String> words = new ArrayList<>();
        for (String w : raw) {
            if (w.matches("[-–—]+") || w.startsWith("(")) {
                break;
            }
            String word = EDGE_PUNCTUATION.matcher(w).replaceAll("");
            if (!word.isEmpty()) {
                words.add(word);
            }
            if (w.endsWith(",")) {
                break;
            }
        }
        return words;
    }

    private static String capped(String last) {
        return last == null || last.length() <= MAX_LAST_NAME ? last : last.substring(0, MAX_LAST_NAME);
    }

    private static String after(List<String> words, String firstName) {
        if (firstName == null) {
            return null;
        }
        String[] first = firstName.trim().split("\\s+");
        for (int i = 0; i + first.length <= words.size(); i++) {
            boolean match = true;
            for (int j = 0; j < first.length && match; j++) {
                match = words.get(i + j).equalsIgnoreCase(first[j]);
            }
            if (match) {
                List<String> rest = words.subList(i + first.length, words.size());
                return rest.isEmpty() ? null : String.join(" ", rest);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- apply / revert

    public record ApplyResult(long applied, long changed, long skippedStale, List<Long> staleSamples,
                              CharacterNameLabelService.ApplyReport labels) {}

    public record RevertResult(long reverted, long skippedStale, List<Long> staleSamples) {}

    private record Staged(long characterId, String oldFirstName, String oldLastName, String oldNameType,
                          String oldParserVersion, Double oldParseConfidence, String newFirstName,
                          String newLastName, String newNameType, Double confidence, String parserVersion,
                          boolean changed, String appliedFirstName, String appliedLastName, String appliedNameType) {
        /** What the last apply wrote (null type when this row has never been re-planned after an apply). */
        String writtenFirstName() {
            return appliedNameType != null ? appliedFirstName : newFirstName;
        }

        String writtenLastName() {
            return appliedNameType != null ? appliedLastName : newLastName;
        }

        String writtenNameType() {
            return appliedNameType != null ? appliedNameType : newNameType;
        }
    }

    /**
     * Writes staged parser results onto characters that still hold the values the dry run recorded (or,
     * for a row re-planned by a refresh, what its previous apply wrote), then applies decisive labels.
     * Safe to re-run: applied rows are not picked up again.
     */
    public ApplyResult apply() {
        total(count("SELECT COUNT(*) FROM character_reparse_rows WHERE applied_at IS NULL AND source IN ('PARSER', 'MODEL')"));
        long processed = 0;
        long applied = 0;
        long changed = 0;
        long stale = 0;
        List<Long> staleSamples = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<Staged> rows = staged("applied_at IS NULL AND source IN ('PARSER', 'MODEL')", lastId);
            if (rows.isEmpty()) {
                break;
            }
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            long[] counts = tx.execute(status -> {
                int[][] updated = jdbc.batchUpdate("""
                    UPDATE characters
                    SET first_name = ?, last_name = ?, name_type = ?, parser_version = ?, parse_confidence = ?,
                        updated_at = ?
                    WHERE id = ? AND manually_verified = FALSE
                      AND ((first_name IS NOT DISTINCT FROM ? AND last_name IS NOT DISTINCT FROM ?
                            AND name_type IS NOT DISTINCT FROM ?)
                        OR (name_type = ? AND first_name IS NOT DISTINCT FROM ? AND last_name IS NOT DISTINCT FROM ?))
                    """, rows, rows.size(), (ps, r) -> {
                    setString(ps, 1, r.newFirstName());
                    setString(ps, 2, r.newLastName());
                    setString(ps, 3, r.newNameType());
                    setString(ps, 4, r.parserVersion());
                    setDouble(ps, 5, r.confidence());
                    ps.setTimestamp(6, now);
                    ps.setLong(7, r.characterId());
                    setString(ps, 8, r.oldFirstName());
                    setString(ps, 9, r.oldLastName());
                    setString(ps, 10, r.oldNameType());
                    setString(ps, 11, r.appliedNameType());   // null: never applied before, so no second match
                    setString(ps, 12, r.appliedFirstName());
                    setString(ps, 13, r.appliedLastName());
                });
                return markApplied(rows, updated[0], now, staleSamples);
            });
            applied += counts[0];
            changed += counts[1];
            stale += counts[2];
            processed += rows.size();
            progress(processed);
            lastId = rows.get(rows.size() - 1).characterId();
        }

        CharacterNameLabelService.ApplyReport labels = labelService.apply(false, null);
        queueService.invalidate();
        ApplyResult result = new ApplyResult(applied, changed, stale, staleSamples, labels);
        log.info("Character re-parse applied: {} rows ({} changed), {} skipped as edited since the dry run",
            applied, changed, stale);
        return result;
    }

    /** @return {applied, changed, stale} for the chunk; sets applied_at on the rows that were written */
    private long[] markApplied(List<Staged> rows, int[] updated, Timestamp now, List<Long> staleSamples) {
        List<Object[]> done = new ArrayList<>();
        long changed = 0;
        long stale = 0;
        for (int i = 0; i < rows.size(); i++) {
            Staged r = rows.get(i);
            if (updated[i] == 0) {
                stale++;
                if (staleSamples.size() < MAX_STALE_SAMPLES) {
                    staleSamples.add(r.characterId());
                }
                continue;
            }
            done.add(new Object[] {now, r.characterId()});
            if (r.changed()) {
                changed++;
            }
        }
        if (!done.isEmpty()) {
            jdbc.batchUpdate("UPDATE character_reparse_rows SET applied_at = ?, applied_first_name = new_first_name, "
                + "applied_last_name = new_last_name, applied_name_type = new_name_type WHERE character_id = ?", done);
        }
        return new long[] {done.size(), changed, stale};
    }

    /** Restores the recorded old values on applied rows that still hold what the apply wrote. */
    public RevertResult revert() {
        total(appliedRows());
        long processed = 0;
        long reverted = 0;
        long stale = 0;
        List<Long> staleSamples = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<Staged> rows = staged("applied_at IS NOT NULL AND source IN ('PARSER', 'MODEL')", lastId);
            if (rows.isEmpty()) {
                break;
            }
            long[] counts = tx.execute(status -> {
                int[][] updated = jdbc.batchUpdate("""
                    UPDATE characters
                    SET first_name = ?, last_name = ?, name_type = ?, parser_version = ?, parse_confidence = ?
                    WHERE id = ? AND manually_verified = FALSE AND first_name IS NOT DISTINCT FROM ?
                      AND last_name IS NOT DISTINCT FROM ? AND name_type IS NOT DISTINCT FROM ?
                    """, rows, rows.size(), (ps, r) -> {
                    setString(ps, 1, r.oldFirstName());
                    setString(ps, 2, r.oldLastName());
                    setString(ps, 3, r.oldNameType());
                    setString(ps, 4, r.oldParserVersion());
                    setDouble(ps, 5, r.oldParseConfidence());
                    ps.setLong(6, r.characterId());
                    setString(ps, 7, r.writtenFirstName());
                    setString(ps, 8, r.writtenLastName());
                    setString(ps, 9, r.writtenNameType());
                });
                List<Object[]> done = new ArrayList<>();
                long skipped = 0;
                for (int i = 0; i < rows.size(); i++) {
                    if (updated[0][i] == 0) {
                        skipped++;
                        if (staleSamples.size() < MAX_STALE_SAMPLES) {
                            staleSamples.add(rows.get(i).characterId());
                        }
                    } else {
                        done.add(new Object[] {rows.get(i).characterId()});
                    }
                }
                if (!done.isEmpty()) {
                    jdbc.batchUpdate("UPDATE character_reparse_rows SET applied_at = NULL WHERE character_id = ?", done);
                }
                return new long[] {done.size(), skipped};
            });
            reverted += counts[0];
            stale += counts[1];
            processed += rows.size();
            progress(processed);
            lastId = rows.get(rows.size() - 1).characterId();
        }
        queueService.invalidate();
        RevertResult result = new RevertResult(reverted, stale, staleSamples);
        log.info("Character re-parse reverted: {}", result);
        return result;
    }

    private List<Staged> staged(String where, long afterId) {
        return jdbc.query("SELECT " + ROW_COLUMNS + ", applied_first_name, applied_last_name, applied_name_type "
            + "FROM character_reparse_rows WHERE " + where
            + " AND character_id > ? ORDER BY character_id LIMIT ?", (rs, i) -> new Staged(
            rs.getLong("character_id"), rs.getString("old_first_name"), rs.getString("old_last_name"),
            rs.getString("old_name_type"), rs.getString("old_parser_version"),
            rs.getObject("old_parse_confidence", Double.class), rs.getString("new_first_name"),
            rs.getString("new_last_name"), rs.getString("new_name_type"), rs.getObject("confidence", Double.class),
            rs.getString("parser_version"), rs.getBoolean("changed"), rs.getString("applied_first_name"),
            rs.getString("applied_last_name"), rs.getString("applied_name_type")), afterId, CHUNK);
    }

    private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private long stagedRows() {
        return count("SELECT COUNT(*) FROM character_reparse_rows");
    }

    private long appliedRows() {
        return count("SELECT COUNT(*) FROM character_reparse_rows WHERE applied_at IS NOT NULL");
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- staged rows

    public enum Change { NAME_TO_NULL, NULL_TO_NAME, NAME_TO_OTHER_NAME, UNCHANGED_NAME, UNCHANGED_NULL }

    public record StagedRow(long characterId, String fullName, String oldFirstName, String oldLastName,
                            String oldNameType, String newFirstName, String newLastName, String newNameType,
                            Double confidence, String source, boolean applied) {}

    /**
     * Staged rows for auditing, filtered by kind of first-name change and confidence, in random order
     * (a fresh sample each call) unless a first name is given, in which case ordered by id.
     */
    public List<StagedRow> rows(Change change, Double minConfidence, Double maxConfidence, String oldFirstName,
                                String newFirstName, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("1 = 1");
        if (change != null) {
            where.append(switch (change) {
                case NAME_TO_NULL -> " AND old_first_name IS NOT NULL AND new_first_name IS NULL";
                case NULL_TO_NAME -> " AND old_first_name IS NULL AND new_first_name IS NOT NULL";
                case NAME_TO_OTHER_NAME -> " AND old_first_name IS NOT NULL AND new_first_name IS NOT NULL"
                    + " AND LOWER(old_first_name) <> LOWER(new_first_name)";
                case UNCHANGED_NAME -> " AND old_first_name IS NOT NULL AND LOWER(old_first_name) = LOWER(new_first_name)";
                case UNCHANGED_NULL -> " AND old_first_name IS NULL AND new_first_name IS NULL";
            });
        }
        if (minConfidence != null) {
            where.append(" AND confidence >= ?");
            params.add(minConfidence);
        }
        if (maxConfidence != null) {
            where.append(" AND confidence < ?");
            params.add(maxConfidence);
        }
        if (oldFirstName != null && !oldFirstName.isBlank()) {
            where.append(" AND old_first_name = ?");
            params.add(oldFirstName.trim());
        }
        if (newFirstName != null && !newFirstName.isBlank()) {
            where.append(" AND new_first_name = ?");
            params.add(newFirstName.trim());
        }
        boolean byName = params.stream().anyMatch(String.class::isInstance);
        params.add(Math.min(Math.max(1, limit), 1000));
        return jdbc.query("SELECT character_id, full_name, old_first_name, old_last_name, old_name_type, new_first_name, "
            + "new_last_name, new_name_type, confidence, source, applied_at FROM character_reparse_rows WHERE " + where
            + (byName ? " ORDER BY character_id" : " ORDER BY RANDOM()") + " LIMIT ?", (rs, i) -> new StagedRow(
            rs.getLong("character_id"), rs.getString("full_name"), rs.getString("old_first_name"),
            rs.getString("old_last_name"), rs.getString("old_name_type"), rs.getString("new_first_name"),
            rs.getString("new_last_name"), rs.getString("new_name_type"), rs.getObject("confidence", Double.class),
            rs.getString("source"), rs.getTimestamp("applied_at") != null), params.toArray());
    }

    // ---------------------------------------------------------------- lexicon export

    private static final Pattern GIVEN_TOKEN = Pattern.compile("^\\p{L}[\\p{L}'\\-]*\\p{L}$");

    /**
     * How many distinct people (actors and crew) have each first word of their full name, for building
     * the parser's person-name lexicon. Initials and punctuation-only words are skipped; keys are the
     * lexicon's folded form. Only words borne by at least {@code minPeople} people are returned.
     */
    public SortedMap<String, Integer> personFirstNameCounts(int minPeople) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT full_name FROM people WHERE full_name IS NOT NULL", rs -> {
            String[] words = rs.getString(1).trim().split("\\s+");
            if (words.length > 0 && GIVEN_TOKEN.matcher(words[0]).matches()) {
                counts.merge(CharacterNameLexicon.fold(words[0]), 1, Integer::sum);
            }
        });
        SortedMap<String, Integer> result = new TreeMap<>();
        counts.forEach((name, n) -> {
            if (n >= Math.max(1, minPeople)) {
                result.put(name, n);
            }
        });
        return result;
    }

    // ---------------------------------------------------------------- report

    public record Summary(long characters, long staged, long skippedVerified, long fromLabels, long applied,
                          long unchanged, long changed, long nameToNull, long nullToName, long nameToOtherName,
                          long casingOnly, long surnameOrTypeOnly, long lowConfidence, long lowConfidenceWithName) {}

    /** A first-name change seen on {@code characters} rows, with a few raw strings as examples. */
    public record Transition(String from, String to, long characters, List<String> examples) {}

    /** A name in a year's ranking; {@code otherRank} is its rank on the other side, null if it has none. */
    public record RankedName(String name, int rank, BigDecimal revenue, Integer otherRank) {}

    /** Top names for a year (null = all years) before and after, with what entered and left the top. */
    public record YearRanking(Integer year, List<RankedName> before, List<RankedName> after,
                              List<RankedName> entered, List<RankedName> left) {}

    public record Report(Instant generatedAt, String parserVersion, int top, Summary summary,
                         List<Transition> removedNames, List<Transition> addedNames, List<Transition> changedNames,
                         YearRanking allYears,
                         List<YearRanking> years) {}

    /**
     * Compares the staged values with the values recorded before them (the same whether or not the run
     * is applied); characters that were not staged count the same on both sides. Rankings use the same measure as the character
     * side of the name pages: summed movie revenue over credits, per release year.
     */
    public Report report(int top, Integer fromYear, Integer toYear) {
        int size = Math.min(Math.max(1, top), 1000);
        CachedReport cached = cachedReport;
        if (cached != null && cached.top() == size && Objects.equals(cached.fromYear(), fromYear)
                && Objects.equals(cached.toYear(), toYear) && cached.computedAt().isAfter(Instant.now().minus(REPORT_TTL))) {
            return cached.report();
        }
        Report report = computeReport(size, fromYear, toYear);
        cachedReport = new CachedReport(size, fromYear, toYear, Instant.now(), report);
        return report;
    }

    private Report computeReport(int size, Integer fromYear, Integer toYear) {
        long characters = count("SELECT COUNT(*) FROM characters");
        long verified = count("SELECT COUNT(*) FROM characters WHERE manually_verified = TRUE");

        long[] c = new long[12];
        Map<String, TransitionCounter> removed = new HashMap<>();
        Map<String, TransitionCounter> renamed = new HashMap<>();
        Map<String, TransitionCounter> added = new HashMap<>();
        jdbc.query("""
            SELECT full_name, old_first_name, new_first_name, confidence, source, changed, applied_at
            FROM character_reparse_rows
            """, rs -> {
            String fullName = rs.getString("full_name");
            String before = rs.getString("old_first_name");
            String after = rs.getString("new_first_name");
            boolean label = Source.LABEL.name().equals(rs.getString("source"));
            double confidence = rs.getDouble("confidence");
            c[0]++;
            if (label) {
                c[1]++;
            }
            if (rs.getTimestamp("applied_at") != null) {
                c[2]++;
            }
            if (!rs.getBoolean("changed")) {
                c[3]++;
            } else {
                c[4]++;
                if (before != null && after == null) {
                    c[5]++;
                    removed.computeIfAbsent(before, k -> new TransitionCounter(k, null)).add(fullName);
                } else if (before == null && after != null) {
                    c[6]++;
                    added.computeIfAbsent(after, k -> new TransitionCounter(null, k)).add(fullName);
                } else if (before != null && !before.equals(after)) {
                    if (before.equalsIgnoreCase(after)) {
                        c[8]++;
                    } else {
                        c[7]++;
                        renamed.computeIfAbsent(before + "\u0000" + after, k -> new TransitionCounter(before, after))
                            .add(fullName);
                    }
                } else {
                    c[9]++;
                }
            }
            if (!label && confidence < LOW_CONFIDENCE) {
                c[10]++;
                if (after != null) {
                    c[11]++;
                }
            }
        });
        Summary summary = new Summary(characters, c[0], verified, c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
            c[9], c[10], c[11]);

        Map<Integer, Map<String, BigDecimal>> before = new TreeMap<>();
        Map<Integer, Map<String, BigDecimal>> after = new TreeMap<>();
        Map<String, BigDecimal> beforeAll = new HashMap<>();
        Map<String, BigDecimal> afterAll = new HashMap<>();
        List<Object> params = new ArrayList<>();
        StringBuilder years = new StringBuilder();
        if (fromYear != null) {
            years.append(" AND m.release_date >= ?");
            params.add(java.sql.Date.valueOf(fromYear + "-01-01"));
        }
        if (toYear != null) {
            years.append(" AND m.release_date <= ?");
            params.add(java.sql.Date.valueOf(toYear + "-12-31"));
        }
        jdbc.query("""
            SELECT EXTRACT(YEAR FROM m.release_date) AS release_year,
                   CASE WHEN s.character_id IS NULL THEN ch.first_name ELSE s.old_first_name END AS before_name,
                   CASE WHEN s.character_id IS NULL THEN ch.first_name ELSE s.new_first_name END AS after_name,
                   SUM(m.revenue) AS revenue
            FROM credits cr
            JOIN characters ch ON ch.id = cr.character_id
            JOIN movies m ON m.id = cr.movie_id
            LEFT JOIN character_reparse_rows s ON s.character_id = ch.id
            WHERE m.revenue IS NOT NULL AND m.release_date IS NOT NULL%s
            GROUP BY EXTRACT(YEAR FROM m.release_date),
                     CASE WHEN s.character_id IS NULL THEN ch.first_name ELSE s.old_first_name END,
                     CASE WHEN s.character_id IS NULL THEN ch.first_name ELSE s.new_first_name END
            """.formatted(years), rs -> {
            int year = rs.getInt("release_year");
            BigDecimal revenue = rs.getBigDecimal("revenue");
            String b = rs.getString("before_name");
            String a = rs.getString("after_name");
            if (b != null) {
                before.computeIfAbsent(year, k -> new HashMap<>()).merge(b, revenue, BigDecimal::add);
                beforeAll.merge(b, revenue, BigDecimal::add);
            }
            if (a != null) {
                after.computeIfAbsent(year, k -> new HashMap<>()).merge(a, revenue, BigDecimal::add);
                afterAll.merge(a, revenue, BigDecimal::add);
            }
        }, params.toArray());

        SortedSet<Integer> allYears = new TreeSet<>(before.keySet());
        allYears.addAll(after.keySet());
        List<YearRanking> perYear = allYears.stream()
            .map(y -> ranking(y, before.getOrDefault(y, Map.of()), after.getOrDefault(y, Map.of()), size))
            .toList();

        return new Report(Instant.now(), CharacterNameParserV2.VERSION, size, summary, topTransitions(removed),
            topTransitions(added),
            topTransitions(renamed), ranking(null, beforeAll, afterAll, size), perYear);
    }

    private static final class TransitionCounter {
        final String from;
        final String to;
        long count;
        final List<String> examples = new ArrayList<>();

        TransitionCounter(String from, String to) {
            this.from = from;
            this.to = to;
        }

        void add(String fullName) {
            count++;
            if (examples.size() < EXAMPLES_PER_TRANSITION) {
                examples.add(fullName);
            }
        }
    }

    private static List<Transition> topTransitions(Map<String, TransitionCounter> counters) {
        return counters.values().stream()
            .sorted(Comparator.comparingLong((TransitionCounter t) -> t.count).reversed()
                .thenComparing(t -> String.valueOf(t.from) + "\u0000" + t.to))
            .limit(MAX_TRANSITIONS)
            .map(t -> new Transition(t.from, t.to, t.count, List.copyOf(t.examples)))
            .toList();
    }

    static YearRanking ranking(Integer year, Map<String, BigDecimal> before, Map<String, BigDecimal> after, int top) {
        Map<String, Integer> beforeRanks = ranks(before);
        Map<String, Integer> afterRanks = ranks(after);
        List<RankedName> b = topOf(before, beforeRanks, afterRanks, top);
        List<RankedName> a = topOf(after, afterRanks, beforeRanks, top);
        List<RankedName> entered = a.stream().filter(n -> n.otherRank() == null || n.otherRank() > top).toList();
        List<RankedName> left = b.stream().filter(n -> n.otherRank() == null || n.otherRank() > top).toList();
        return new YearRanking(year, b, a, entered, left);
    }

    private static Map<String, Integer> ranks(Map<String, BigDecimal> revenue) {
        List<String> order = revenue.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            ranks.put(order.get(i), i + 1);
        }
        return ranks;
    }

    private static List<RankedName> topOf(Map<String, BigDecimal> revenue, Map<String, Integer> ranks,
                                          Map<String, Integer> otherRanks, int top) {
        return ranks.entrySet().stream()
            .filter(e -> e.getValue() <= top)
            .sorted(Map.Entry.comparingByValue())
            .map(e -> new RankedName(e.getKey(), e.getValue(), revenue.get(e.getKey()), otherRanks.get(e.getKey())))
            .toList();
    }
}

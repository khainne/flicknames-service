package com.flicknames.service.service;

import com.flicknames.service.service.CharacterNameModelService.BatchInfo;
import com.flicknames.service.service.CharacterNameModelService.StageResult;
import com.flicknames.service.service.CharacterNameModelService.SubmitResult;
import com.flicknames.service.service.CharacterNameReparseService.JobState;
import com.flicknames.service.service.CharacterNameReparseService.ReparseConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps new credits going through the model pass. Each {@link #tick} takes at most one step, so a step that
 * runs in the background (a re-parse dry run or apply) finishes before the next tick looks again:
 * <ol>
 *   <li>an open batch is collected; once it has ended its answers are staged (with the usual screens) and
 *       the staged rows applied;</li>
 *   <li>otherwise, at most once per {@code submitEvery}, characters not staged yet (new from the collector)
 *       are staged with the parser, once per cycle even if more keep arriving;</li>
 *   <li>then the staged credits that have no model decision and no {@link CertainParses} rule are sent as
 *       one batch of at most {@code maxItems}.</li>
 * </ol>
 * It does nothing while the model is not configured or a re-parse step is running, and leaves a batch an
 * admin submitted to be collected the same way.
 */
@Service
@Slf4j
public class CharacterNameModelAutoPass {

    /** Above every parser confidence: send all staged credits that no certainty rule covers. */
    static final double ALL_CONFIDENCES = 1.01;

    public record Outcome(String step, String detail) {}

    private final CharacterNameModelService modelService;
    private final CharacterNameReparseService reparseService;
    private final CharacterNameModel model;
    private final JdbcTemplate jdbc;
    private final int maxItems;
    private final Duration submitEvery;
    /** When new characters were last staged; once per cycle, so a collector that keeps adding them does not hold up the batch. */
    private LocalDateTime stagedNewAt;

    public CharacterNameModelAutoPass(CharacterNameModelService modelService, CharacterNameReparseService reparseService,
                                      CharacterNameModel model, JdbcTemplate jdbc,
                                      @Value("${character-name-model.schedule.max-items:20000}") int maxItems,
                                      @Value("${character-name-model.schedule.submit-every-hours:24}") long submitEveryHours) {
        this.modelService = modelService;
        this.reparseService = reparseService;
        this.model = model;
        this.jdbc = jdbc;
        this.maxItems = maxItems;
        this.submitEvery = Duration.ofHours(submitEveryHours);
    }

    public synchronized Outcome tick() {
        try {
            return step();
        } catch (ReparseConflictException e) {
            return new Outcome("SKIPPED", e.getMessage());
        }
    }

    private Outcome step() {
        if (!model.configured()) {
            return new Outcome("SKIPPED", "The Claude API key is not set.");
        }
        if (reparseService.status().map(s -> s.state() == JobState.RUNNING).orElse(false)) {
            return new Outcome("SKIPPED", "A re-parse step is running.");
        }
        List<String> open = jdbc.queryForList(
            "SELECT batch_id FROM character_model_batches WHERE collected_at IS NULL ORDER BY submitted_at", String.class);
        if (!open.isEmpty()) {
            return collect(open.get(0));
        }
        Timestamp last = jdbc.queryForObject("SELECT MAX(submitted_at) FROM character_model_batches", Timestamp.class);
        if (last != null && last.toLocalDateTime().plus(submitEvery).isAfter(LocalDateTime.now())) {
            return new Outcome("WAITING", "Next batch after " + last.toLocalDateTime().plus(submitEvery) + ".");
        }
        Long unstaged = jdbc.queryForObject("""
            SELECT COUNT(*) FROM characters ch
            LEFT JOIN character_reparse_rows s ON s.character_id = ch.id
            WHERE s.character_id IS NULL AND ch.manually_verified = FALSE""", Long.class);
        if (unstaged != null && unstaged > 0
                && (stagedNewAt == null || stagedNewAt.plus(submitEvery).isBefore(LocalDateTime.now()))) {
            reparseService.startDryRun(false, true);
            stagedNewAt = LocalDateTime.now();
            return new Outcome("STAGING_NEW", unstaged + " new characters are being staged with the parser.");
        }
        SubmitResult preview = modelService.submit(ALL_CONFIDENCES, maxItems, true, true);
        if (preview.sent() == 0) {
            return new Outcome("NOTHING_TO_SEND", "Every staged credit has a model decision or a certain parse.");
        }
        SubmitResult submitted = modelService.submit(ALL_CONFIDENCES, maxItems, true, false);
        log.info("Model pass: sent {} credits in batch {} (about ${})", submitted.sent(),
            submitted.batch().batchId(), String.format("%.2f", submitted.estimatedCostUsd()));
        return new Outcome("SUBMITTED", submitted.sent() + " credits in batch " + submitted.batch().batchId()
            + String.format(", about $%.2f.", submitted.estimatedCostUsd()));
    }

    private Outcome collect(String batchId) {
        BatchInfo info = modelService.collect(batchId);
        if (info.collectedAt() == null) {
            return new Outcome("WAITING", "Batch " + batchId + " is " + info.status() + ".");
        }
        StageResult stage = modelService.stage(false);
        log.info("Model pass: collected batch {} (${}), staged {}", batchId,
            String.format("%.2f", info.estimatedCostUsd()), stage);
        if (stage.staged() > 0) {
            reparseService.startApply();
        }
        return new Outcome("COLLECTED", String.format("Batch %s: %d decisions, %d changes staged%s, $%.2f.", batchId,
            stage.decisions(), stage.staged(), stage.staged() > 0 ? " and being applied" : "", info.estimatedCostUsd()));
    }
}

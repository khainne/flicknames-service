package com.flicknames.service.service;

import com.anthropic.models.messages.batches.MessageBatch;
import com.flicknames.service.service.CharacterNameModel.Answer;
import com.flicknames.service.service.CharacterNameModel.Decision;
import com.flicknames.service.service.CharacterNameModel.Item;
import com.flicknames.service.service.CharacterNameModel.Usage;
import com.flicknames.service.service.CharacterNameModelAutoPass.Outcome;
import com.flicknames.service.service.CharacterNameReparseService.JobState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The scheduled model pass, one tick at a time, with the model mocked. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:model_auto_pass_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CharacterNameModelAutoPassTests {

    @Autowired
    private CharacterNameModelAutoPass autoPass;

    @Autowired
    private CharacterNameReparseService reparseService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private CharacterNameModel model;

    @Test
    void stagesNewCreditsSubmitsCollectsAndAppliesThenWaitsADay() {
        when(model.configured()).thenReturn(false);
        assertThat(autoPass.tick().step()).isEqualTo("SKIPPED");

        when(model.configured()).thenReturn(true);
        jdbc.update("INSERT INTO characters (full_name, first_name, last_name, name_type, manually_verified, created_at, updated_at) "
            + "VALUES ('Ojciec', 'Ojciec', NULL, 'SINGLE_NAME', FALSE, NOW(), NOW())");

        assertThat(autoPass.tick().step()).isEqualTo("STAGING_NEW");
        awaitReparse();
        // A credit the collector adds meanwhile waits for the next cycle rather than holding up the batch
        jdbc.update("INSERT INTO characters (full_name, first_name, last_name, name_type, manually_verified, created_at, updated_at) "
            + "VALUES ('Late Arrival', 'Late', 'Arrival', 'STANDARD', FALSE, NOW(), NOW())");

        MessageBatch submitted = mock(MessageBatch.class);
        when(submitted.id()).thenReturn("msgbatch_auto");
        when(submitted.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        when(model.submit(anyMap())).thenReturn(submitted);
        Outcome sent = autoPass.tick();
        assertThat(sent.step()).isEqualTo("SUBMITTED");
        verify(model).submit(anyMap());

        MessageBatch running = mock(MessageBatch.class);
        when(running.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        when(model.retrieve("msgbatch_auto")).thenReturn(running);
        assertThat(autoPass.tick().step()).isEqualTo("WAITING");

        MessageBatch ended = mock(MessageBatch.class);
        when(ended.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(model.retrieve("msgbatch_auto")).thenReturn(ended);
        doAnswer(inv -> {
            Map<String, List<Item>> byRequest = inv.getArgument(1);
            Consumer<Map.Entry<String, Answer>> sink = inv.getArgument(2);
            byRequest.forEach((customId, chunk) -> {
                List<Decision> decisions = new ArrayList<>();
                // the model says "Ojciec" (Polish for "father") has no first name; it agrees elsewhere
                chunk.forEach(item -> decisions.add(new Decision(item.id(),
                    item.fullName().equals("Ojciec") ? null : parserFirstName(item.id()))));
                sink.accept(Map.entry(customId, new Answer(decisions, new Usage(1000, 200, 0), null)));
            });
            return null;
        }).when(model).results(eq("msgbatch_auto"), anyMap(), any());

        Outcome collected = autoPass.tick();
        assertThat(collected.step()).isEqualTo("COLLECTED");
        awaitReparse();
        assertThat(jdbc.queryForObject("SELECT first_name FROM characters WHERE full_name = 'Ojciec'", String.class)).isNull();

        // The next batch waits a day
        assertThat(autoPass.tick().step()).isEqualTo("WAITING");
        verify(model, times(1)).submit(anyMap());
    }

    private String parserFirstName(long characterId) {
        return jdbc.queryForObject("SELECT new_first_name FROM character_reparse_rows WHERE character_id = ?",
            String.class, characterId);
    }

    private void awaitReparse() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (reparseService.status().map(s -> s.state() == JobState.RUNNING).orElse(false)) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.onSpinWait();
        }
        assertThat(reparseService.status().orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
    }
}

package com.flicknames.service.service;

import com.anthropic.models.messages.batches.MessageBatch;
import com.flicknames.service.service.CharacterNameModel.Answer;
import com.flicknames.service.service.CharacterNameModel.Decision;
import com.flicknames.service.service.CharacterNameModel.Item;
import com.flicknames.service.service.CharacterNameModel.Usage;
import com.flicknames.service.service.CharacterNameModelService.BatchInfo;
import com.flicknames.service.service.CharacterNameModelService.StageResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The model pass end to end with the model mocked: submit, collect, stage, apply, refresh, revert. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:model_pass_test;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CharacterNameModelServiceTests {

    @Autowired
    private CharacterNameModelService modelService;

    @Autowired
    private CharacterNameReparseService reparseService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private CharacterNameModel model;

    @Test
    @SuppressWarnings("unchecked")
    void submitCollectStageApplyRevert() {
        when(model.configured()).thenReturn(true);
        jdbc.update("INSERT INTO characters (full_name, first_name, last_name, name_type, manually_verified, created_at, updated_at) "
            + "VALUES ('Ojciec', 'Ojciec', NULL, 'SINGLE_NAME', FALSE, NOW(), NOW()), "
            + "('Groot', 'Groot', NULL, 'SINGLE_NAME', FALSE, NOW(), NOW())");
        reparseService.dryRun();
        reparseService.apply();
        assertThat(staged("Ojciec", "confidence", Double.class)).isLessThan(0.7);
        assertThat(firstName("Ojciec")).isEqualTo("Ojciec");

        MessageBatch submitted = mock(MessageBatch.class);
        when(submitted.id()).thenReturn("msgbatch_test");
        when(submitted.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        when(model.submit(anyMap())).thenReturn(submitted);

        BatchInfo batch = modelService.submit(0.7, 1000);
        ArgumentCaptor<Map<String, List<Item>>> sent = ArgumentCaptor.forClass(Map.class);
        verify(model).submit(sent.capture());
        List<Item> items = sent.getValue().values().stream().flatMap(List::stream).toList();
        assertThat(items).extracting(Item::fullName).contains("Ojciec", "Groot");
        assertThat(batch.items()).isEqualTo(items.size());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM character_model_decisions WHERE status = 'PENDING'",
            Integer.class)).isEqualTo(items.size());
        // One open batch at a time
        assertThatThrownBy(() -> modelService.submit(0.7, 1000)).isInstanceOf(IllegalStateException.class);

        MessageBatch ended = mock(MessageBatch.class);
        when(ended.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(model.retrieve("msgbatch_test")).thenReturn(ended);
        doAnswer(inv -> {
            Map<String, List<Item>> byRequest = inv.getArgument(1);
            Consumer<Map.Entry<String, Answer>> sink = inv.getArgument(2);
            byRequest.forEach((customId, chunk) -> {
                List<Decision> decisions = new ArrayList<>();
                for (Item item : chunk) {
                    if (item.fullName().equals("Ojciec")) {
                        decisions.add(new Decision(item.id(), null));
                    } else if (item.fullName().equals("Groot")) {
                        decisions.add(new Decision(item.id(), "Groot"));
                    }
                }
                sink.accept(Map.entry(customId, new Answer(decisions, new Usage(1000, 200, 0), null)));
            });
            return null;
        }).when(model).results(eq("msgbatch_test"), anyMap(), any());

        BatchInfo collected = modelService.collect("msgbatch_test");
        assertThat(collected.collectedAt()).isNotNull();
        assertThat(collected.inputTokens()).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM character_model_decisions WHERE full_name = 'Ojciec'",
            String.class)).isEqualTo("DONE");

        StageResult stage = modelService.stage(false);
        assertThat(stage.staged()).isEqualTo(1);   // Ojciec; Groot agrees with the parser
        assertThat(staged("Ojciec", "source", String.class)).isEqualTo("MODEL");
        assertThat(firstName("Ojciec")).isEqualTo("Ojciec");   // nothing written before apply

        reparseService.apply();
        assertThat(firstName("Ojciec")).isNull();
        assertThat(jdbc.queryForObject("SELECT parser_version FROM characters WHERE full_name = 'Ojciec'", String.class))
            .isEqualTo(CharacterNameModel.MODEL);

        // A parser refresh leaves the model's decision alone
        reparseService.dryRun(true, true);
        assertThat(staged("Ojciec", "source", String.class)).isEqualTo("MODEL");
        assertThat(firstName("Ojciec")).isNull();

        reparseService.revert();
        assertThat(firstName("Ojciec")).isEqualTo("Ojciec");   // the recorded value from before the first apply
    }

    @Test
    void screenedOutAnswersFallBackToTheParser() {
        assertThat(CharacterNameModelService.screenedOut("Young", "Griff", 0.85)).isTrue();
        assertThat(CharacterNameModelService.screenedOut("Mr.", null, 0.85)).isTrue();
        assertThat(CharacterNameModelService.screenedOut("Captain Sorenson", null, 0.85)).isTrue();
        assertThat(CharacterNameModelService.screenedOut("Inmate", null, 0.95)).isTrue();
        assertThat(CharacterNameModelService.screenedOut("Dance", null, 0.85)).isTrue();
        assertThat(CharacterNameModelService.screenedOut("Newt", null, 0.6)).isFalse();   // parser unsure
        assertThat(CharacterNameModelService.screenedOut("Griff", "Young", 0.5)).isFalse();
        assertThat(CharacterNameModelService.screenedOut("Jin", "Huang", 0.5)).isFalse();
        assertThat(CharacterNameModelService.screenedOut(null, "Anna", 0.9)).isFalse();
        assertThat(reparseService.parserPlan("Young Griff").firstName()).isEqualTo("Griff");
    }

    @Test
    void parseDropsUnknownIdsDuplicatesAndInventedNames() {
        List<Item> items = List.of(new Item(1, "Huang Jin", null, null, null, null),
            new Item(2, "Ojciec", null, null, null, null), new Item(3, "Oncle Fred", null, null, null, null));
        Answer answer = CharacterNameModel.parseText("""
            {"results":[{"id":1,"firstName":"Jin"},{"id":2,"firstName":null},{"id":3,"firstName":"Frederick"},
                        {"id":1,"firstName":"Huang"},{"id":99,"firstName":"Bob"}]}""", items, null);
        assertThat(answer.error()).isNull();
        assertThat(answer.decisions()).containsExactly(new Decision(1, "Jin"), new Decision(2, null));
        assertThat(CharacterNameModel.parseText("not json", items, null).error()).startsWith("unreadable JSON");
    }

    @Test
    void userMessageCarriesTheCreditAndItsContext() {
        String message = CharacterNameModel.userMessage(List.of(
            new Item(7, "Liu San (Falcon Buyer)", "Ip Man 4", 2019, "zh", "Some Actor"),
            new Item(8, "Bare \"quoted\"", null, null, null, null)));
        assertThat(message).contains("7. \"Liu San (Falcon Buyer)\" - movie \"Ip Man 4\" (2019, zh); actor \"Some Actor\"")
            .contains("8. \"Bare \\\"quoted\\\"\"\n");
    }

    @Test
    void planForMapsAnAnswerToStoredParts() {
        assertThat(CharacterNameModelService.planFor("Ojciec", null, "Ojciec", null, "SINGLE_NAME").firstName()).isNull();
        assertThat(CharacterNameModelService.planFor("Groot", "Groot", "Groot", null, "SINGLE_NAME")).isNull();
        assertThat(CharacterNameModelService.planFor("Liu San (Falcon Buyer)", "San", "Liu", "San", "STANDARD"))
            .satisfies(p -> {
                assertThat(p.firstName()).isEqualTo("San");
                assertThat(p.lastName()).isNull();
                assertThat(p.nameType()).isEqualTo("STANDARD");
            });
        assertThat(CharacterNameModelService.planFor("Kirkkoherra Rauno Ylämäki", "Rauno", "Kirkkoherra", null, "STANDARD")
            .lastName()).isEqualTo("Ylämäki");
        assertThat(CharacterNameModelService.planFor("Azamat", "Azamat", null, null, "ROLE_DESCRIPTION").nameType())
            .isEqualTo("SINGLE_NAME");
    }

    private String firstName(String fullName) {
        return jdbc.queryForObject("SELECT first_name FROM characters WHERE full_name = ?", String.class, fullName);
    }

    private <T> T staged(String fullName, String column, Class<T> type) {
        return jdbc.queryForObject("SELECT " + column + " FROM character_reparse_rows WHERE full_name = ?", type, fullName);
    }
}

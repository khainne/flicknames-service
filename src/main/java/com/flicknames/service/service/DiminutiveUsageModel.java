package com.flicknames.service.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.entity.DiminutiveUsage.Usage;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Asks Claude whether each short form is still used as a nickname for its full form or is an independent
 * given name today. Uses the same model and key as {@link CharacterNameModel}.
 */
@Component
public class DiminutiveUsageModel {

    public static final int PAIRS_PER_REQUEST = 50;

    public record Pair(long id, String shortForm, String fullForm) {}

    public record Result(Map<Long, Usage> usages, long inputTokens, long outputTokens, String error) {}

    static final String SYSTEM_PROMPT = """
        You help a site about first names group names into families. For each pair "X -> Y", answer one \
        question: today, do people commonly call someone named Y by X, as a nickname or short form? Think of \
        English-speaking use unless both names are clearly from another language; then use that language.
        - "nickname" when yes, even if X is also popular as a name of its own: Jack for John, Ollie for Oliver, \
        Max for Maxine, Abby for Abigail, Ben for Bennett, Annie for Ann, Wes for Wesley, Penny for Penelope, \
        Maddie for Madison, Kit for Christopher, Nick for Nicholas, Pepe for José, Peggy for Margaret.
        - "independent" only when people named Y are rarely called X today, because X long ago became a \
        separate name: Liam for William, Colin for Nicholas, Robin for Robert, Greta for Margaret, Marion for \
        Mary, Janet for Jane. Also "independent" when X is not a short form of Y at all but a different name \
        (Angelina for Angelica).
        If unsure, answer "nickname". Answer every id exactly once.""";


    private static final ObjectMapper JSON = new ObjectMapper();

    private final CharacterNameModel model;

    public DiminutiveUsageModel(CharacterNameModel model) {
        this.model = model;
    }

    public boolean configured() {
        return model.configured();
    }

    public Result classify(List<Pair> pairs) {
        StringBuilder user = new StringBuilder("Pairs (id. short form -> full form):\n");
        pairs.forEach(p -> user.append(p.id()).append(". ").append(p.shortForm()).append(" -> ").append(p.fullForm()).append('\n'));
        Message message = model.client().messages().create(MessageCreateParams.builder()
            .model(CharacterNameModel.MODEL)
            .maxTokens(4000)
            .system(SYSTEM_PROMPT)
            .outputConfig(outputConfig())
            .addUserMessage(user.toString())
            .build());
        long in = message.usage().inputTokens();
        long out = message.usage().outputTokens();
        if (!StopReason.END_TURN.equals(message.stopReason().orElse(null))) {
            return new Result(Map.of(), in, out, "stop_reason " + message.stopReason().orElse(null));
        }
        String text = message.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).reduce("", String::concat);
        return parse(text, pairs, in, out);
    }

    static Result parse(String text, List<Pair> pairs, long in, long out) {
        Set<Long> ids = new HashSet<>();
        pairs.forEach(p -> ids.add(p.id()));
        Map<Long, Usage> usages = new HashMap<>();
        try {
            for (JsonNode node : JSON.readTree(text).path("results")) {
                long id = node.path("id").asLong(-1);
                String usage = node.path("usage").asText("");
                if (ids.contains(id) && !usages.containsKey(id)) {
                    if (usage.equals("nickname")) {
                        usages.put(id, Usage.NICKNAME);
                    } else if (usage.equals("independent")) {
                        usages.put(id, Usage.INDEPENDENT);
                    }
                }
            }
            return new Result(usages, in, out, null);
        } catch (Exception e) {
            return new Result(Map.of(), in, out, "unreadable JSON: " + e.getMessage());
        }
    }

    private static OutputConfig outputConfig() {
        Map<String, Object> item = Map.of(
            "type", "object",
            "properties", Map.of(
                "id", Map.of("type", "integer"),
                "usage", Map.of("type", "string", "enum", List.of("nickname", "independent"))),
            "required", List.of("id", "usage"),
            "additionalProperties", false);
        return OutputConfig.builder()
            .effort(OutputConfig.Effort.MEDIUM)
            .format(JsonOutputFormat.builder().schema(JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of("results", Map.of("type", "array", "items", item))))
                .putAdditionalProperty("required", JsonValue.from(List.of("results")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()).build())
            .build();
    }
}

package com.flicknames.service.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.util.charactername.CharacterNameLexicon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

/**
 * Asks a Claude model for the first name in hard character credits - the ones parser v2 is unsure of.
 * One request carries up to {@link #ITEMS_PER_REQUEST} credits, each with the movie and actor as
 * context, and gets back structured JSON. Reads the key from {@code ANTHROPIC_API_KEY} or, as the Railway
 * service names it, {@code CLAUDE_API_KEY}; without one,
 * {@link #configured()} is false and nothing is sent.
 */
@Component
public class CharacterNameModel {

    /** The low-cost model the owner chose for this pass. Fits the 16-char parser_version column. */
    public static final String MODEL = "claude-haiku-5-5";
    public static final int ITEMS_PER_REQUEST = 50;
    private static final long MAX_TOKENS = 8000;

    /** One credit to decide. {@code id} is only echoed back, so answers can be matched to rows. */
    public record Item(long id, String fullName, String movieTitle, Integer year, String language, String actorName) {}

    /** The model's answer for one item: the first name, or null when the credit carries none. */
    public record Decision(long id, String firstName) {}

    /** Tokens one response used, for the cost record. */
    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens) {
        static Usage of(Message message) {
            return new Usage(message.usage().inputTokens(), message.usage().outputTokens(),
                message.usage().cacheReadInputTokens().orElse(0L));
        }
    }

    /** A parsed response, or the reason there is none (refusal, truncation, malformed JSON). */
    public record Answer(List<Decision> decisions, Usage usage, String error) {}

    static final String SYSTEM_PROMPT = """
        You label character credits from movie cast lists (TMDB) for a site about first names in film. \
        For each credit, give the given first name a parent could use as a baby name, or null when the \
        credit carries no usable first name. You get the raw credit plus its movie, year, original \
        language and the credited actor; use them to read the credit, not to replace it.

        Rules:
        - A person's name gives its given name: "Teri Montana" -> Teri, "Kaede Rukawa (voice)" -> Kaede, \
        "Terry (10 Yrs Old)" -> Terry, "Jack (segment 'The Lake')" -> Jack.
        - East Asian names are usually written given-name first in TMDB ("Tsumugu Kawamura" -> Tsumugu). \
        When the credit is clearly surname-first (a common Chinese, Korean or Japanese surname first, often \
        in a film in that language: "Huang Jin" -> Jin, "Watanabe Seiji" -> Seiji), give the given name.
        - Nicknames in quotes: use the real given name ("Franklin 'Frank' Pearl" -> Franklin). A nickname with \
        only a surname counts when it is a plausible name.
        - Strip titles, ranks, age or kin prefixes and role words before a given name: "Sir William Beechey" \
        -> William, "Young Anna" -> Anna, "Princess Leia" -> Leia, "Porteiro Roberto" -> Roberto, \
        "Oncle Fred" -> Fred. A title or role word with only a surname has no first name: "Officer Dunn", \
        "Dr. Lipschweiss", "Master Seo", "Colonel Adachi" -> null. A bare surname ("Halloran") -> null.
        - Roles, occupations, descriptions, groups, creatures, numbered extras, relational roles ("Erika's \
        Son", "Madre de Miguel"), and Self/Himself/Herself credits -> null, in any language: "Stock Broker", \
        "Umpire #3 (voice)", "Ojciec", "Empregada", "Stormtrooper", "Female Celt", "Townsperson".
        - A single word that is an ordinary word in English or another language -> null ("Jelly", "Rocket"). \
        A single word that reads as a personal name counts, including invented fictional names \
        ("Azamat", "Boudica", "Yoda", "T'Challa").
        - Multi-role credits ("A / B"): use the first role that has a name ("Jack Lapidus / Soundtrack \
        Vocalist" -> Jack).
        - Name followed by a description: "Fred the Janitor" -> Fred. A name that is also a word but is \
        used as a name before a surname counts ("Guy Gardner" -> Guy, "Christian Weller" -> Christian).
        - Write the name in normal capitalisation (Anna, not ANNA) and keep hyphenated given names whole \
        ("Seong-hun", "Jean-Luc"). The name must appear in the credit; never invent or translate one.

        Answer every id you are given exactly once.""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private volatile AnthropicClient client;

    public CharacterNameModel(@Value("${ANTHROPIC_API_KEY:${CLAUDE_API_KEY:}}") String apiKey,
                              @Value("${anthropic.base-url:}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    if (!configured()) {
                        throw new IllegalStateException("ANTHROPIC_API_KEY / CLAUDE_API_KEY is not set");
                    }
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
                    if (!baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl);   // tests point this at a local server
                    }
                    client = builder.build();
                }
                c = client;
            }
        }
        return c;
    }

    /** Decides one request's worth of items synchronously (for the pilot). */
    public Answer classify(List<Item> items) {
        Message message = client().messages().create(MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(system())
            .outputConfig(outputConfig())
            .addUserMessage(userMessage(items))
            .build());
        return parse(message, items);
    }

    /** Submits one Message Batch; the keys of {@code requests} become the custom ids. */
    public MessageBatch submit(Map<String, List<Item>> requests) {
        BatchCreateParams.Builder batch = BatchCreateParams.builder();
        requests.forEach((customId, items) -> batch.addRequest(BatchCreateParams.Request.builder()
            .customId(customId)
            .params(BatchCreateParams.Request.Params.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(system())
                .outputConfig(outputConfig())
                .addUserMessage(userMessage(items))
                .build())
            .build()));
        return client().messages().batches().create(batch.build());
    }

    public MessageBatch retrieve(String batchId) {
        return client().messages().batches().retrieve(batchId);
    }

    /** Streams a finished batch's results; each comes with the items of its request (by custom id). */
    public void results(String batchId, Map<String, List<Item>> itemsByRequest, Consumer<Map.Entry<String, Answer>> sink) {
        try (StreamResponse<MessageBatchIndividualResponse> stream = client().messages().batches().resultsStreaming(batchId)) {
            stream.stream().forEach(r -> {
                List<Item> items = itemsByRequest.getOrDefault(r.customId(), List.of());
                Answer answer = r.result().succeeded()
                    .map(s -> parse(s.message(), items))
                    .orElseGet(() -> new Answer(List.of(), null,
                        r.result().isErrored() ? "errored" : r.result().isExpired() ? "expired" : "canceled"));
                sink.accept(Map.entry(r.customId(), answer));
            });
        }
    }

    private static List<TextBlockParam> system() {
        return List.of(TextBlockParam.builder().text(SYSTEM_PROMPT)
            .cacheControl(CacheControlEphemeral.builder().build()).build());
    }

    private static OutputConfig outputConfig() {
        Map<String, Object> decision = Map.of(
            "type", "object",
            "properties", Map.of(
                "id", Map.of("type", "integer"),
                "firstName", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null")))),
            "required", List.of("id", "firstName"),
            "additionalProperties", false);
        return OutputConfig.builder()
            .effort(OutputConfig.Effort.LOW)
            .format(JsonOutputFormat.builder().schema(JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "results", Map.of("type", "array", "items", decision))))
                .putAdditionalProperty("required", JsonValue.from(List.of("results")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()).build())
            .build();
    }

    static String userMessage(List<Item> items) {
        StringBuilder out = new StringBuilder("Credits:\n");
        for (Item item : items) {
            out.append(item.id()).append(". ").append(quote(item.fullName()));
            List<String> context = new ArrayList<>();
            if (item.movieTitle() != null) {
                String movie = "movie " + quote(item.movieTitle());
                List<String> facts = new ArrayList<>();
                if (item.year() != null) {
                    facts.add(String.valueOf(item.year()));
                }
                if (item.language() != null && !item.language().isBlank()) {
                    facts.add(item.language());
                }
                context.add(facts.isEmpty() ? movie : movie + " (" + String.join(", ", facts) + ")");
            }
            if (item.actorName() != null) {
                context.add("actor " + quote(item.actorName()));
            }
            if (!context.isEmpty()) {
                out.append(" - ").append(String.join("; ", context));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    /**
     * Reads the JSON answer. Ids not in the request are dropped, and a name that does not occur in its
     * credit (case- and accent-insensitive) is dropped too rather than trusted.
     */
    static Answer parse(Message message, List<Item> items) {
        Usage usage = Usage.of(message);
        StopReason stop = message.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop)) {
            return new Answer(List.of(), usage, "stop_reason " + stop);
        }
        String text = message.content().stream()
            .flatMap(block -> block.text().stream())
            .map(t -> t.text())
            .reduce("", String::concat);
        return parseText(text, items, usage);
    }

    static Answer parseText(String text, List<Item> items, Usage usage) {
        Map<Long, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        try {
            JsonNode results = JSON.readTree(text).path("results");
            List<Decision> decisions = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (JsonNode node : results) {
                long id = node.path("id").asLong(-1);
                Item item = byId.get(id);
                if (item == null || !seen.add(id)) {
                    continue;
                }
                JsonNode name = node.path("firstName");
                String firstName = name.isTextual() && !name.asText().isBlank() ? name.asText().strip() : null;
                if (firstName != null && !occursIn(firstName, item.fullName())) {
                    continue;   // not in the credit: an invented or translated name
                }
                decisions.add(new Decision(id, firstName));
            }
            return new Answer(decisions, usage, null);
        } catch (Exception e) {
            return new Answer(List.of(), usage, "unreadable JSON: " + e.getMessage());
        }
    }

    private static boolean occursIn(String name, String credit) {
        String n = CharacterNameLexicon.fold(name);
        String c = CharacterNameLexicon.fold(credit);
        return c.contains(n);
    }
}

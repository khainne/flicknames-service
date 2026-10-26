package com.flicknames.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.service.CharacterNameModel.Answer;
import com.flicknames.service.service.CharacterNameModel.Decision;
import com.flicknames.service.service.CharacterNameModel.Item;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** A real SDK round trip against a local server: the request it builds and the response it reads. */
class CharacterNameModelTest {

    @Test
    void classifySendsTheModelSchemaAndContextAndReadsTheAnswer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":[{"type":"text","text":"{\\"results\\":[{\\"id\\":1,\\"firstName\\":\\"Jin\\"},{\\"id\\":2,\\"firstName\\":null}]}"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":1200,"output_tokens":40,"cache_read_input_tokens":0}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            CharacterNameModel model = new CharacterNameModel("test-key", "http://127.0.0.1:" + server.getAddress().getPort());
            Answer answer = model.classify(List.of(
                new Item(1, "Huang Jin", "Some Film", 2004, "zh", "Huang Jin Actor"),
                new Item(2, "Ojciec", null, null, null, null)));

            assertThat(answer.error()).isNull();
            assertThat(answer.decisions()).containsExactly(new Decision(1, "Jin"), new Decision(2, null));
            assertThat(answer.usage().inputTokens()).isEqualTo(1200);

            JsonNode request = new ObjectMapper().readTree(body.get());
            assertThat(request.path("model").asText()).isEqualTo(CharacterNameModel.MODEL);
            assertThat(request.path("output_config").path("effort").asText()).isEqualTo("low");
            assertThat(request.path("output_config").path("format").path("schema").path("required").get(0).asText())
                .isEqualTo("results");
            assertThat(request.path("system").get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
            assertThat(request.path("messages").get(0).path("content").asText())
                .contains("1. \"Huang Jin\" - movie \"Some Film\" (2004, zh)");
        } finally {
            server.stop(0);
        }
    }
}

package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.IToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LlmObservationTest {
    private record Event(String type, Map<String, ?> data) {}

    @Test void realLocalDispatchRetainsWholeWireRequestAndPairsVisibleResponseAcrossRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        List<JsonObject> received = new CopyOnWriteArrayList<>();
        server.createContext("/chat/completions", exchange -> {
            received.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            String output = attempts.incrementAndGet() == 1 ? "busy"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"可见答案\",\"reasoning_content\":\"do not record\","
                    + "\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"plan\",\"arguments\":\"{\\\"count\\\":\"}}]},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(attempts.get() == 1 ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            List<Event> events = new CopyOnWriteArrayList<>();
            var observation = new LlmObservation("supervisor", "companion-1", "stage_b",
                    (type, data) -> events.add(new Event(type, data)));
            IToolSpec tool = new IToolSpec() {
                public String name() { return "plan"; }
                public String description() { return "Count exact material quantities"; }
                public Map<String, Object> parameterSchema() {
                    return Map.of("type", "object", "properties", Map.of("count", Map.of("type", "integer")));
                }
            };
            var client = NumenLlmClient.forEndpoint(new LlmEndpoint("openai", "local-test-model", "test-secret",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "auto"));
            var result = client.chatStreaming(List.of(new ConvoState.Msg.User("物资22，要多少？\n" + "知识".repeat(3000))),
                    List.of(tool), "完整system\n不要制造假成功", null, observation).get(10, TimeUnit.SECONDS);
            assertEquals("可见答案", result.turn().content());
            assertEquals("{}", result.turn().toolCalls().get(0).arguments());
            assertEquals(List.of("llm_request", "llm_request", "llm_response"), events.stream().map(Event::type).toList());
            assertEquals(2, received.size());
            assertEquals(received.get(0), events.get(0).data().get("request"));
            assertEquals(received.get(1), events.get(1).data().get("request"));
            assertEquals(1, events.get(0).data().get("attempt"));
            assertEquals(2, events.get(1).data().get("attempt"));
            assertEquals("dispatched", events.get(1).data().get("status"));
            assertEquals(1, events.stream().map(e -> e.data().get("requestId")).distinct().count());
            JsonObject response = (JsonObject) events.get(2).data().get("response");
            assertEquals("{\"count\":", response.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("arguments").getAsString());
            assertFalse(events.toString().contains("test-secret"));
            assertFalse(events.toString().contains("do not record"));
        } finally { server.stop(0); }
    }

    @Test void excludesHiddenReasoningAndCredentialEchoWithoutChangingWireOrThinkingMode() {
        JsonObject original = JsonParser.parseString("""
                {"thinking":{"type":"enabled","budget_tokens":1024},
                 "messages":[{"role":"assistant","reasoning_content":"hidden", "content":[
                   {"type":"thinking","thinking":"private", "signature":"opaque"},
                   {"type":"text","text":"visible key-123"}]}],
                 "tools":[{"name":"craft","parameters":{"type":["object","null"],
                   "properties":{"reasoning":{"type":null},"signature":{"type":{"custom":true}}}}}],
                 "other":[{"type":null},{"type":["object","null"]},{"type":{"custom":true}}]}
                """).getAsJsonObject();
        String before = original.toString();
        String visible = LlmObservation.visibleCopy(original, "key-123").toString();
        assertEquals(before, original.toString());
        assertFalse(visible.contains("hidden"));
        assertFalse(visible.contains("private"));
        assertFalse(visible.contains("opaque"));
        assertFalse(visible.contains("key-123"));
        assertTrue(visible.contains("budget_tokens"));
        assertTrue(visible.contains("visible"));
        assertTrue(visible.contains("craft"));
        assertEquals(original.get("tools"), LlmObservation.visibleCopy(original, "key-123").getAsJsonObject().get("tools"));
        assertEquals(original.get("other"), LlmObservation.visibleCopy(original, "key-123").getAsJsonObject().get("other"));
        assertDoesNotThrow(() -> new LlmObservation("numen", "id", "execution", (a, b) -> {
            throw new IllegalStateException("disk unavailable");
        }).publish("llm_request", "model", Map.of("request", original), "key-123"));
    }

    @Test void rejectedRequestPairsFailureWithoutRecordingServerBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "private server body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            List<Event> events = new CopyOnWriteArrayList<>();
            var client = NumenLlmClient.forEndpoint(new LlmEndpoint("openai", "local-test-model", "test-secret",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "auto"));
            var future = client.chatStreaming(List.of(new ConvoState.Msg.User("hello")), List.of(), "system", null,
                    new LlmObservation("numen", "id", "execution", (type, data) -> events.add(new Event(type, data))));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertEquals(List.of("llm_request", "llm_failure"), events.stream().map(Event::type).toList());
            assertEquals(events.get(0).data().get("requestId"), events.get(1).data().get("requestId"));
            assertFalse(events.toString().contains("private server body"));
        } finally { server.stop(0); }
    }
}

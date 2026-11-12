package io.virbius.engine.eval.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.virbius.engine.config.PromptJudgeProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PromptJudgeBackendHttpTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void startLuxPostsSystemOneWithModelAndParsesNoul() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MeterRegistry registry = new SimpleMeterRegistry();
        StartLuxJudgeBackend backend = new StartLuxJudgeBackend(
                props("startlux-4b", "http://judge", "StartLux-Decision-v1.1-4B-Instant", 4000),
                mapper, registry, builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "StartLux-Decision-v1.1-4B-Instant",
                          "state": {"content": "现在能买吗？"},
                          "questions": {
                            "r0": {"type": "noul", "instructions": "是否请求具体基金买卖建议"}
                          }
                        }
                        """, true))
                .andRespond(withSuccess(
                        "{\"model\":\"StartLux-Decision-v1.1-4B-Instant\","
                                + "\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.87}},"
                                + "\"usage\":{\"input_tokens\":9,\"output_tokens\":12}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertEquals(0.87, result.answers().get("r0").probability());
        assertNull(result.answers().get("r0").error());
        assertEquals(1.0, requests(registry, "startlux-4b", "ok"), 0.0);
    }

    @Test
    void neoHorsePostsSystemOneWithModel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, new SimpleMeterRegistry(), builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andExpect(content().json("""
                        {
                          "model": "NeoHorse-Jev-4B",
                          "state": {"content": "现在能买吗？"},
                          "questions": {
                            "r0": {"type": "noul", "instructions": "是否请求具体基金买卖建议"}
                          }
                        }
                        """, true))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.12}}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertEquals(0.12, result.answers().get("r0").probability());
    }

    @Test
    void eightQuestionsGoIntoOneRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<JsonNode> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", ex -> {
            requests.incrementAndGet();
            bodyRef.set(mapper.readTree(ex.getRequestBody().readAllBytes()));
            List<String> ids = new ArrayList<>();
            bodyRef.get().path("questions").fieldNames().forEachRemaining(ids::add);
            StringBuilder answers = new StringBuilder("{\"answers\":{");
            int i = 0;
            for (String id : ids) {
                if (i++ > 0) {
                    answers.append(',');
                }
                answers.append('"').append(id).append("\":{\"type\":\"noul\",\"noul\":0.9}");
            }
            answers.append("}}");
            byte[] out = answers.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(props(
                    "neohorse-4b",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "NeoHorse-Jev-4B", 4000), mapper, new SimpleMeterRegistry());

            List<PromptJudgeBackend.JudgeRule> rules = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                rules.add(new PromptJudgeBackend.JudgeRule("R" + i, "noul", "criterion " + i));
            }
            PromptJudgeBackend.JudgeResult result =
                    backend.judge(new PromptJudgeBackend.JudgeRequest("hello", rules, true));

            assertEquals(1, requests.get());
            assertNull(result.error());
            assertEquals("NeoHorse-Jev-4B", bodyRef.get().path("model").asText());
            assertEquals(8, bodyRef.get().path("questions").size());
            assertEquals(8, result.answers().size());
            for (int i = 0; i < 8; i++) {
                assertNull(result.answers().get("R" + i).error());
                assertEquals(0.9, result.answers().get("R" + i).probability());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingAnswerIsReportedPerRule() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LayaJudgeBackend backend = new LayaJudgeBackend(
                props("laya", "http://judge", "laya", 4000),
                mapper, new SimpleMeterRegistry(), builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(withSuccess("{\"answers\":{}}", MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNotNull(result.answers().get("r0").error());
        assertTrue(result.answers().get("r0").error().contains("missing answer"));
    }

    @Test
    void noulOutOfRangeIsClamped() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, new SimpleMeterRegistry(), builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":1.7}}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNull(result.answers().get("r0").error());
        assertEquals(1.0, result.answers().get("r0").probability());
    }

    @Test
    void nonNumericNoulFailsPerRule() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, new SimpleMeterRegistry(), builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":\"0.9\"}}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNotNull(result.answers().get("r0").error());
        assertTrue(result.answers().get("r0").error().contains("missing noul"));
    }

    @Test
    void busyResponseIsRetriedOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MeterRegistry registry = new SimpleMeterRegistry();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, registry, builder.build());

        HttpHeaders busyHeaders = new HttpHeaders();
        busyHeaders.add("Retry-After", "0");
        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatusCode.valueOf(529))
                        .headers(busyHeaders)
                        .body("{\"detail\":\"Model busy; retry later\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.55}}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNull(result.error());
        assertEquals(0.55, result.answers().get("r0").probability());
        assertEquals(1.0, requests(registry, "neohorse-4b", "ok"), 0.0);
    }

    @Test
    void busyWithoutRetryFailsImmediately() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MeterRegistry registry = new SimpleMeterRegistry();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, registry, builder.build());

        HttpHeaders busyHeaders = new HttpHeaders();
        busyHeaders.add("Retry-After", "0");
        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatusCode.valueOf(529))
                        .headers(busyHeaders)
                        .body("{\"detail\":\"Model busy\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(
                new PromptJudgeBackend.JudgeRequest("现在能买吗？", request().rules(), false));

        server.verify();
        assertNotNull(result.error());
        assertTrue(result.error().contains("529"));
        assertTrue(result.answers().isEmpty());
        assertEquals(1.0, requests(registry, "neohorse-4b", "busy"), 0.0);
    }

    @Test
    void httpErrorSurfacesStatusAndDetail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MeterRegistry registry = new SimpleMeterRegistry();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, registry, builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatusCode.valueOf(500))
                        .body("{\"detail\":\"boom\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNotNull(result.error());
        assertTrue(result.error().contains("500"));
        assertTrue(result.error().contains("boom"));
        assertTrue(result.answers().isEmpty());
        assertEquals(1.0, requests(registry, "neohorse-4b", "http_5xx"), 0.0);
    }

    @Test
    void blankModelFailsWithoutHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "", 4000),
                mapper, new SimpleMeterRegistry(), builder.build());

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNotNull(result.error());
        assertTrue(result.error().contains("no model"));
        assertTrue(result.answers().isEmpty());
    }

    @Test
    void oversizedContentIsTruncatedHeadAndTail() throws Exception {
        AtomicReference<JsonNode> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", ex -> {
            bodyRef.set(mapper.readTree(ex.getRequestBody().readAllBytes()));
            byte[] out = "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.5}}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            MeterRegistry registry = new SimpleMeterRegistry();
            NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(props(
                    "neohorse-4b",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "NeoHorse-Jev-4B", 100), mapper, registry);

            String head = "HEADMARK" + "a".repeat(40);
            String tail = "b".repeat(40) + "TAILMARK";
            String content = head + "x".repeat(5000) + tail;

            PromptJudgeBackend.JudgeResult result = backend.judge(
                    new PromptJudgeBackend.JudgeRequest(content, request().rules(), true));

            assertNull(result.error());
            String sent = bodyRef.get().path("state").path("content").asText();
            assertTrue(sent.contains("HEADMARK"));
            assertTrue(sent.contains("TAILMARK"));
            assertTrue(sent.contains("[truncated "));
            assertTrue(sent.length() < content.length());
            assertEquals(1, registry.counter("virbius.prompt.judge.content.truncated",
                    "backend", "neohorse-4b").count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chineseContentTruncatedToTokenBudget() throws Exception {
        AtomicReference<JsonNode> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", ex -> {
            bodyRef.set(mapper.readTree(ex.getRequestBody().readAllBytes()));
            byte[] out = "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.5}}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            MeterRegistry registry = new SimpleMeterRegistry();
            NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(props(
                    "neohorse-4b",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "NeoHorse-Jev-4B", 4000), mapper, registry);

            String content = "中".repeat(3000);
            PromptJudgeBackend.JudgeResult result = backend.judge(
                    new PromptJudgeBackend.JudgeRequest(content, request().rules(), true));

            assertNull(result.error());
            String sent = bodyRef.get().path("state").path("content").asText();
            assertTrue(sent.contains("[truncated "));
            assertTrue(sent.length() < content.length());
            assertTrue(sent.length() <= 2048, "sent chars=" + sent.length());
            assertEquals(1, registry.counter("virbius.prompt.judge.content.truncated",
                    "backend", "neohorse-4b").count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metricsRecordLatencyBatchAndNoul() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MeterRegistry registry = new SimpleMeterRegistry();
        NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(
                props("neohorse-4b", "http://judge", "NeoHorse-Jev-4B", 4000),
                mapper, registry, builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.7}}}",
                        MediaType.APPLICATION_JSON));

        backend.judge(request());

        server.verify();
        assertEquals(1, registry.summary("virbius.prompt.judge.latency.ms",
                "backend", "neohorse-4b").count());
        assertEquals(1, registry.summary("virbius.prompt.judge.batch.questions",
                "backend", "neohorse-4b").count());
        assertEquals(1, registry.summary("virbius.prompt.judge.noul",
                "backend", "neohorse-4b").count());
    }

    private static double requests(MeterRegistry registry, String backend, String result) {
        return registry.counter("virbius.prompt.judge.requests",
                "backend", backend, "result", result).count();
    }

    private static PromptJudgeProperties props(
            String id, String baseUrl, String model, int maxContentChars) {
        return new PromptJudgeProperties(
                id, 1000, 8, 24000, maxContentChars,
                Map.of(id, new PromptJudgeProperties.Backend(
                        baseUrl, true, model, "", 8)));
    }

    private static PromptJudgeBackend.JudgeRequest request() {
        return new PromptJudgeBackend.JudgeRequest(
                "现在能买吗？",
                List.of(new PromptJudgeBackend.JudgeRule(
                        "r0", "noul", "是否请求具体基金买卖建议")), true);
    }
}

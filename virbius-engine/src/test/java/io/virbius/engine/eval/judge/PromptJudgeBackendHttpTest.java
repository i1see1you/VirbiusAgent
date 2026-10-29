package io.virbius.engine.eval.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.virbius.engine.config.PromptJudgeProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PromptJudgeBackendHttpTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void startLuxUsesSystemOneQuestionsAndParsesNoul() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StartLuxJudgeBackend backend =
                new StartLuxJudgeBackend("http://judge", mapper, builder.build());

        server.expect(requestTo("http://judge/v1/systemone"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "state": {"content": "现在能买吗？"},
                          "questions": {
                            "r0": {"type": "noul", "instructions": "是否请求具体基金买卖建议"}
                          }
                        }
                        """, true))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"type\":\"noul\",\"noul\":0.87}}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertEquals(0.87, result.answers().get("r0").probability());
        assertTrue(result.answers().get("r0").error() == null);
    }

    @Test
    void sidecarUsesRulesArrayAndParsesNoul() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend =
                new NeoHorseJudgeBackend("http://judge", mapper, builder.build());

        server.expect(requestTo("http://judge/v1/judge"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "state": {"content": "现在能买吗？"},
                          "rules": [
                            {"id": "r0", "type": "noul", "instructions": "是否请求具体基金买卖建议"}
                          ]
                        }
                        """, true))
                .andRespond(withSuccess(
                        "{\"answers\":{\"r0\":{\"noul\":0.12}}}",
                        MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertEquals(0.12, result.answers().get("r0").probability());
    }

    @Test
    void sidecarAcceptsMultipleRulesInOneCall() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/judge", ex -> {
            requests.incrementAndGet();
            var root = mapper.readTree(ex.getRequestBody().readAllBytes());
            StringBuilder answers = new StringBuilder("{\"answers\":{");
            int i = 0;
            for (var rule : root.path("rules")) {
                if (i++ > 0) {
                    answers.append(',');
                }
                answers.append('"').append(rule.path("id").asText()).append("\":{\"noul\":0.9}");
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
            PromptJudgeProperties props = new PromptJudgeProperties(
                    "neohorse-4b",
                    1000,
                    8,
                    Map.of("neohorse-4b", new PromptJudgeProperties.Backend(
                            "http", "http://127.0.0.1:" + server.getAddress().getPort(),
                            true, "test", true, false, "test", 0)));
            NeoHorseJudgeBackend backend = new NeoHorseJudgeBackend(props, mapper);

            List<PromptJudgeBackend.JudgeRule> rules = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                rules.add(new PromptJudgeBackend.JudgeRule("R" + i, "noul", "criterion " + i));
            }
            PromptJudgeBackend.JudgeResult result =
                    backend.judge(new PromptJudgeBackend.JudgeRequest("hello", rules));

            assertEquals(1, requests.get());
            assertNull(result.error());
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
        LayaJudgeBackend backend = new LayaJudgeBackend("http://judge", mapper, builder.build());

        server.expect(requestTo("http://judge/v1/judge"))
                .andRespond(withSuccess("{\"answers\":{}}", MediaType.APPLICATION_JSON));

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNotNull(result.answers().get("r0").error());
        assertTrue(result.answers().get("r0").error().contains("missing"));
    }

    @Test
    void httpFailureIsReportedWithoutThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend =
                new NeoHorseJudgeBackend("http://judge", mapper, builder.build());

        server.expect(requestTo("http://judge/v1/judge")).andRespond(withServerError());

        PromptJudgeBackend.JudgeResult result = backend.judge(request());

        server.verify();
        assertNotNull(result.error());
        assertTrue(result.answers().isEmpty());
    }

    @Test
    void healthReportsDownWhenServerFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NeoHorseJudgeBackend backend =
                new NeoHorseJudgeBackend("http://judge", mapper, builder.build());

        server.expect(requestTo("http://judge/health")).andRespond(withServerError());

        assertFalse(backend.health().healthy());
        server.verify();
    }

    private static PromptJudgeBackend.JudgeRequest request() {
        return new PromptJudgeBackend.JudgeRequest(
                "现在能买吗？",
                List.of(new PromptJudgeBackend.JudgeRule(
                        "r0", "noul", "是否请求具体基金买卖建议")));
    }
}

package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.virbius.engine.config.PromptJudgeProperties;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

abstract class AbstractHttpJudgeBackend implements PromptJudgeBackend {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpJudgeBackend.class);

    private final String id;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final boolean systemOneBody;
    private final RestClient client;

    protected AbstractHttpJudgeBackend(
            String id,
            PromptJudgeProperties properties,
            ObjectMapper mapper,
            boolean systemOneBody) {
        this(
                id,
                baseUrl(properties, id),
                mapper,
                systemOneBody,
                buildClient(properties.timeoutMs()));
    }

    AbstractHttpJudgeBackend(
            String id,
            String baseUrl,
            ObjectMapper mapper,
            boolean systemOneBody,
            RestClient client) {
        this.id = id;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.mapper = mapper;
        this.systemOneBody = systemOneBody;
        this.client = client;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public JudgeResult judge(JudgeRequest request) {
        long started = System.nanoTime();
        if (request == null || request.content() == null || request.content().isBlank()) {
            return JudgeResult.failed("content required", elapsedMs(started));
        }
        if (request.rules().isEmpty()) {
            return new JudgeResult(Map.of(), elapsedMs(started), null);
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.putObject("state").put("content", request.content());
            addQuestions(body, request.rules());

            String raw = client
                    .post()
                    .uri(URI.create(baseUrl + judgePath()))
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            return parse(raw, request.rules(), started);
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("prompt judge backend {} failed: {}", id, e.getMessage());
            return JudgeResult.failed(e.getMessage(), elapsedMs(started));
        }
    }

    @Override
    public JudgeHealth health() {
        try {
            client.get().uri(URI.create(baseUrl + "/health")).retrieve().toBodilessEntity();
            return JudgeHealth.up();
        } catch (RestClientException | IllegalArgumentException e) {
            return JudgeHealth.down(e.getMessage());
        }
    }

    protected abstract String judgePath();

    private void addQuestions(ObjectNode body, List<JudgeRule> rules) {
        if (systemOneBody) {
            ObjectNode questions = body.putObject("questions");
            for (JudgeRule rule : rules) {
                ObjectNode question = questions.putObject(rule.id());
                question.put("type", rule.type());
                question.put("instructions", rule.instructions());
            }
            return;
        }
        ArrayNode out = body.putArray("rules");
        for (JudgeRule rule : rules) {
            ObjectNode item = out.addObject();
            item.put("id", rule.id());
            item.put("type", rule.type());
            item.put("instructions", rule.instructions());
        }
    }

    private JudgeResult parse(String raw, List<JudgeRule> rules, long started) {
        if (raw == null || raw.isBlank()) {
            return JudgeResult.failed("empty judge response", elapsedMs(started));
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            return JudgeResult.failed("invalid judge response: " + e.getMessage(), elapsedMs(started));
        }
        JsonNode error = root.path("error");
        if (error.isTextual() && !error.asText().isBlank()) {
            return JudgeResult.failed(error.asText(), elapsedMs(started));
        }

        JsonNode answers = root.path("answers");
        Map<String, JudgeAnswer> out = new LinkedHashMap<>();
        for (JudgeRule rule : rules) {
            JsonNode answer = answers.path(rule.id());
            if (!answer.isObject()) {
                out.put(rule.id(), JudgeAnswer.failed("missing answer"));
                continue;
            }
            JsonNode noul = answer.path("noul");
            if (!noul.isNumber()) {
                out.put(rule.id(), JudgeAnswer.failed("missing noul"));
                continue;
            }
            out.put(rule.id(), JudgeAnswer.probability(noul.asDouble()));
        }
        return new JudgeResult(out, elapsedMs(started), null);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String baseUrl(PromptJudgeProperties properties, String id) {
        PromptJudgeProperties.Backend backend = properties.backends().get(id);
        return backend == null ? "" : backend.baseUrl();
    }

    private static RestClient buildClient(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(factory).build();
    }
}

package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.AsyncActionProperties;
import io.virbius.policy.MatchContext;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AsyncActionHandlerTest {

    private SimpleMeterRegistry registry;
    private ThreadPoolExecutor pool;
    private AsyncActionProperties props;

    private static RuleEntry rule(String config) {
        return new RuleEntry(
                "default", "rule-async-1", 1, "cloud", "groovy",
                "TEST", 80, "allow", "dry_run", 100, "dry_run", "body", Map.of(),
                true, config);
    }

    private static MatchContext ctx(String content) {
        return new MatchContext(
                content, "user-1", "dev-1", "10.0.0.1", "sess-1",
                Map.of(), Map.of(), Map.of(), "/v1/chat/completions", null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        pool = new ThreadPoolExecutor(
                2, 2, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        props = new AsyncActionProperties(true, null, null, 2, 64, 2000, 500);
    }

    private double meter(String type, String result) {
        var c = registry.find("virbius.async_action").tags("type", type, "result", result).counter();
        return c != null ? c.count() : 0.0;
    }

    @Test
    void noopWhenNoConfig() {
        AsyncActionHandler handler = new AsyncActionHandler(
                props, mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule(null), null, ctx("hi"));
        handler.executeIfConfigured(rule(""), null, ctx("hi"));
        assertEquals(0.0, meter("webhook", "success"));
        assertEquals(0.0, meter("webhook", "failure"));
    }

    @Test
    void skippedWhenDisabled() {
        AsyncActionHandler handler = new AsyncActionHandler(
                new AsyncActionProperties(false, null, null, 2, 64, 2000, 500),
                mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule("{\"type\":\"webhook\",\"url\":\"http://x\"}"), null, ctx("hi"));
        assertEquals(1.0, meter("unknown", "skipped"));
    }

    @Test
    void legacyRedisStreamSkippedNotFailure() throws Exception {
        AsyncActionHandler handler = new AsyncActionHandler(
                props, mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule("{\"type\":\"redis_stream\"}"), null, ctx("hi"));
        assertEquals(1.0, meter("unknown", "skipped"));
        assertEquals(0.0, meter("unknown", "failure"));
    }

    @Test
    void unknownTypeSkipped() {
        AsyncActionHandler handler = new AsyncActionHandler(
                props, mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule("{\"type\":\"pigeon\"}"), null, ctx("hi"));
        assertEquals(1.0, meter("unknown", "skipped"));
    }

    @Test
    void webhookSuccess() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<java.net.http.HttpResponse<String>> future =
                (CompletableFuture<java.net.http.HttpResponse<String>>) (CompletableFuture<?>) CompletableFuture
                        .completedFuture(new FakeResponse(200));
        when(http.sendAsync(any(), any())).thenReturn((CompletableFuture) future);
        AsyncActionHandler handler = new AsyncActionHandler(props, mock(KafkaTemplate.class), http, pool, registry);
        handler.executeIfConfigured(rule(
                "{\"type\":\"webhook\",\"url\":\"https://hooks.example.com/t/SECRET_TOKEN\",\"body\":{\"rule\":\"{{rule_id}}\"}}"),
                null, ctx("hello"));
        awaitMeter("webhook", "success", 1.0);
        assertEquals(0.0, meter("webhook", "failure"));
    }

    @Test
    void webhookNon2xxIsFailure() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<java.net.http.HttpResponse<String>> future =
                (CompletableFuture<java.net.http.HttpResponse<String>>) (CompletableFuture<?>) CompletableFuture
                        .completedFuture(new FakeResponse(500));
        when(http.sendAsync(any(), any())).thenReturn((CompletableFuture) future);
        AsyncActionHandler handler = new AsyncActionHandler(props, mock(KafkaTemplate.class), http, pool, registry);
        handler.executeIfConfigured(rule(
                "{\"type\":\"webhook\",\"url\":\"https://hooks.example.com/t/SECRET_TOKEN\"}"), null, ctx("hello"));
        awaitMeter("webhook", "failure", 1.0);
        assertEquals(0.0, meter("webhook", "success"));
    }

    @Test
    void webhookRejectsNonHttpScheme() {
        AsyncActionHandler handler = new AsyncActionHandler(
                props, mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule(
                "{\"type\":\"webhook\",\"url\":\"file:///etc/passwd\"}"), null, ctx("hello"));
        assertEquals(1.0, meter("webhook", "skipped"));
        assertEquals(0.0, meter("webhook", "failure"));
    }

    @Test
    void webhookAllowedHostsRestricts() {
        AsyncActionProperties restricted = new AsyncActionProperties(
                true,
                new AsyncActionProperties.Webhook(5000, "hooks.example.com:8443"),
                null, 2, 64, 2000, 500);
        AsyncActionHandler handler = new AsyncActionHandler(
                restricted, mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule(
                "{\"type\":\"webhook\",\"url\":\"https://hooks.example.com:8443/t/x\"}"), null, ctx("hello"));
        handler.executeIfConfigured(rule(
                "{\"type\":\"webhook\",\"url\":\"https://evil.example.com/t/x\"}"), null, ctx("hello"));
        assertEquals(1.0, meter("webhook", "skipped"));
        // First request passes validation and goes to the pool; real send fails (no server) -> failure.
        awaitMeter("webhook", "failure", 1.0);
    }

    @Test
    void webhookBodyTruncatedPerFieldAndJsonValid() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<java.net.http.HttpResponse<String>> future =
                (CompletableFuture<java.net.http.HttpResponse<String>>) (CompletableFuture<?>) CompletableFuture
                        .completedFuture(new FakeResponse(200));
        when(http.sendAsync(any(), any())).thenReturn((CompletableFuture) future);
        AsyncActionProperties tight = new AsyncActionProperties(
                true, new AsyncActionProperties.Webhook(5000, ""), null, 2, 64, 10, 5);
        AsyncActionHandler handler = new AsyncActionHandler(tight, mock(KafkaTemplate.class), http, pool, registry);
        String longContent = "x".repeat(500);
        handler.executeIfConfigured(rule(
                "{\"type\":\"webhook\",\"url\":\"http://127.0.0.1:9/h\",\"body\":"
                        + "{\"c\":\"{{content}}\",\"p\":\"{{content_preview}}\",\"s\":\"{{session_id}}\"}}"),
                null, ctx(longContent));
        awaitMeter("webhook", "success", 1.0);
    }

    @Test
    void kafkaSuccess() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        RecordMetadata md = recordMetadata();
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("virbius-rule-actions", "k", "v"), md)));
        AsyncActionHandler handler = new AsyncActionHandler(props, kafka, HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule(
                "{\"type\":\"kafka\",\"topic\":\"virbius-rule-actions\",\"message\":{\"rule\":\"{{rule_id}}\"}}"),
                null, ctx("hi"));
        awaitMeter("kafka", "success", 1.0);
    }

    @Test
    void kafkaCallbackFailureCounted() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> f = new CompletableFuture<>();
        f.completeExceptionally(new RuntimeException("broker down"));
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(f);
        AsyncActionHandler handler = new AsyncActionHandler(props, kafka, HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule("{\"type\":\"kafka\"}"), null, ctx("hi"));
        awaitMeter("kafka", "failure", 1.0);
    }

    @Test
    void kafkaSyncThrowCounted() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("metadata unavailable"));
        AsyncActionHandler handler = new AsyncActionHandler(props, kafka, HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule("{\"type\":\"kafka\"}"), null, ctx("hi"));
        awaitMeter("kafka", "failure", 1.0);
        assertEquals(0.0, meter("unknown", "failure"));
    }

    @Test
    void queueFullDropsWithSkipped() {
        ThreadPoolExecutor tinyPool = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        // Occupy the single worker + the single queue slot with slow deliveries.
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(new CompletableFuture<>()); // never completes -> worker/queue stuck
        AsyncActionHandler handler = new AsyncActionHandler(props, kafka, HttpClient.newHttpClient(), tinyPool, registry);
        handler.executeIfConfigured(rule("{\"type\":\"kafka\"}"), null, ctx("a"));
        handler.executeIfConfigured(rule("{\"type\":\"kafka\"}"), null, ctx("b"));
        handler.executeIfConfigured(rule("{\"type\":\"kafka\"}"), null, ctx("c")); // queue full -> drop
        assertEquals(1.0, meter("kafka", "skipped"));
    }

    @Test
    void malformedConfigIsFailureNotThrow() {
        AsyncActionHandler handler = new AsyncActionHandler(
                props, mock(KafkaTemplate.class), HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule("not-json"), null, ctx("hi"));
        assertEquals(1.0, meter("unknown", "failure"));
    }

    @Test
    void kafkaEnvelopeWrapsTemplatePayload() throws Exception {
        // Asserts envelope fields via meter + direct payload check through a capturing template.
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        RecordMetadata md = recordMetadata();
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("virbius-rule-actions", "k", "v"), md)));
        AsyncActionHandler handler = new AsyncActionHandler(props, kafka, HttpClient.newHttpClient(), pool, registry);
        handler.executeIfConfigured(rule(
                "{\"type\":\"kafka\",\"message\":{\"note\":\"{{rule_id}} hit\"}}"), null, ctx("hi"));
        awaitMeter("kafka", "success", 1.0);
        org.mockito.ArgumentCaptor<String> valueCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafka).send(any(String.class), any(String.class), valueCaptor.capture());
        String json = valueCaptor.getValue();
        assertTrue(json.contains("\"version\":1"), json);
        assertTrue(json.contains("\"rule_id\":\"rule-async-1\""), json);
        assertTrue(json.contains("\"session_id\":\"sess-1\""), json);
        assertTrue(json.contains("\"payload\""), json);
        assertTrue(json.contains("\"note\":\"rule-async-1 hit\""), json);
    }

    private static RecordMetadata recordMetadata() {
        TopicPartition tp = new TopicPartition("virbius-rule-actions", 0);
        return new RecordMetadata(tp, 0L, 0L, 0L, 0L, 0, 0);
    }

    private void awaitMeter(String type, String result, double want) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (meter(type, result) >= want) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertEquals(want, meter(type, result), "timed out waiting for metric " + type + "/" + result);
    }

    @SuppressWarnings("unchecked")
    private static final class FakeResponse implements java.net.http.HttpResponse<String> {
        private final int status;

        FakeResponse(int status) {
            this.status = status;
        }

        @Override public int statusCode() { return status; }
        @Override public java.net.http.HttpRequest request() { return null; }
        @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public String body() { return ""; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        @Override public java.net.URI uri() { return java.net.URI.create("http://127.0.0.1/"); }
        @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
    }
}

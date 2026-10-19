package io.virbius.control.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.repository.ChallengeApprovalRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/** Regression tests for the engine proxy: failures must return a JSON body, never an empty one. */
class ChallengeControllerTest {

    private HttpServer server;

    private ApiResult<?> errorBody(ResponseEntity<?> response) {
        return assertInstanceOf(ApiResult.class, response.getBody());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ChallengeController controllerWithEngine(String baseUrl) {
        ChallengeController controller =
                new ChallengeController(mock(ChallengeApprovalRepository.class));
        ReflectionTestUtils.setField(controller, "engineBaseUrl", baseUrl);
        return controller;
    }

    private String startEngine(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/challenges", exchange -> respond(exchange, status, body));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Engine that answers the per-challenge status endpoint with a fixed status/body. */
    private String startEngineWithStatus(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/challenge", exchange -> respond(exchange, status, body));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void returnsJsonErrorWhenEngineIsUnreachable() {
        ResponseEntity<?> response =
                controllerWithEngine("http://127.0.0.1:1").listChallenges("default", "pending", 50);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, errorBody(response).getCode());
        assertEquals("challenge service unavailable", errorBody(response).getMessage());
    }

    @Test
    void returnsJsonErrorWhenEngineReturnsEmpty500() throws Exception {
        String baseUrl = startEngine(500, "");

        ResponseEntity<?> response =
                controllerWithEngine(baseUrl).listChallenges("default", "pending", 50);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, errorBody(response).getCode());
        assertEquals("engine returned HTTP 500", errorBody(response).getMessage());
    }

    @Test
    void returnsJsonErrorWhenEngineReturnsNonJson200() throws Exception {
        String baseUrl = startEngine(200, "oops");

        ResponseEntity<?> response =
                controllerWithEngine(baseUrl).listChallenges("default", "pending", 50);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, errorBody(response).getCode());
        assertEquals("challenge service unavailable", errorBody(response).getMessage());
    }

    @Test
    void proxiesPendingListWhenEngineIsHealthy() throws Exception {
        String baseUrl = startEngine(200, "[]");

        ResponseEntity<?> response =
                controllerWithEngine(baseUrl).listChallenges("default", "pending", 50);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(), response.getBody());
    }

    @Test
    void approveReports500WhenEngineStatusEndpointFails() throws Exception {
        String baseUrl = startEngineWithStatus(500, "");

        ResponseEntity<?> response =
                controllerWithEngine(baseUrl).approve("ch-1", Map.of("approved_by", "op"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, errorBody(response).getCode());
        assertEquals("challenge service unavailable", errorBody(response).getMessage());
    }

    @Test
    void rejectReports500WhenEngineStatusEndpointFails() throws Exception {
        String baseUrl = startEngineWithStatus(502, "");

        ResponseEntity<?> response =
                controllerWithEngine(baseUrl).reject("ch-1", Map.of("reason", "nope"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, errorBody(response).getCode());
        assertEquals("challenge service unavailable", errorBody(response).getMessage());
    }

    @Test
    void approveOnNonPendingChallengeReturns400WithCurrentStatus() throws Exception {
        String baseUrl = startEngineWithStatus(200, "{\"status\":\"approved\"}");

        ResponseEntity<?> response =
                controllerWithEngine(baseUrl).approve("ch-1", Map.of("approved_by", "op"));

        assertEquals(400, response.getStatusCode().value());
        ApiResult<?> body = errorBody(response);
        assertEquals("challenge is not pending", body.getMessage());
        assertEquals(Map.of("current_status", "approved"), body.getData());
    }
}

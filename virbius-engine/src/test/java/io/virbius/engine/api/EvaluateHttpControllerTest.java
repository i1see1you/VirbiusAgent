package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class EvaluateHttpControllerTest {

    @Mock
    private EvaluateOrchestrator orchestrator;

    @Mock
    private OutputReviewService outputReview;

    @Mock
    private HttpServletRequest http;

    private EvaluateRateLimiter rateLimiter;
    private EvaluateHttpController controller;

    @BeforeEach
    void setUp() {
        rateLimiter = new EvaluateRateLimiter(true, 10_000, 100_000, 10_000);
        controller = new EvaluateHttpController(orchestrator, outputReview, rateLimiter);
        lenient().when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(orchestrator.evaluate(any())).thenReturn(
                new EvaluateResponseDto("allow", 0, 0, "POLICY_ALLOW", 0,
                        "POLICY_ALLOW", "tr", false, "full", null, null));
        lenient().when(outputReview.review(any())).thenReturn(
                new EvaluateResponseDto("allow", 0, 0, "POLICY_ALLOW", 0,
                        "POLICY_ALLOW", "tr", false, "full", null, null, "warn", null));
    }

    private static EvaluateRequestDto request(String role) {
        return new EvaluateRequestDto(
                "default", role, "sess-1", "hello", false, null,
                "tr-1", "uid-1", null, Map.of(), null, null, null, null,
                "chat_input", null, 100);
    }

    // -- role is a closed enum -------------------------------------------------

    @Test
    void legacyUserRoleIsRejectedWith400() {
        ResponseEntity<?> resp = controller.evaluate(request("user"), http);
        assertEquals(400, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("unsupported_role", body.get("error"));
        assertEquals("user", body.get("role"));
        verify(orchestrator, never()).evaluate(any());
        verify(outputReview, never()).review(any());
    }

    @Test
    void unknownRoleIsRejectedWith400() {
        assertEquals(400, controller.evaluate(request("memory"), http).getStatusCode().value());
        verify(orchestrator, never()).evaluate(any());
    }

    @Test
    void roleIsNormalizedBeforeValidation() {
        // "USER" normalizes to the rejected legacy role; " Tool_Call " and
        // "INPUT" normalize to supported roles.
        assertEquals(400, controller.evaluate(request("USER"), http).getStatusCode().value());
        assertEquals(200, controller.evaluate(request(" Tool_Call "), http).getStatusCode().value());
        assertEquals(200, controller.evaluate(request("INPUT"), http).getStatusCode().value());
        verify(orchestrator, org.mockito.Mockito.times(2)).evaluate(any());
        verify(outputReview, never()).review(any());
    }

    @Test
    void blankRoleDefaultsToToolCallPipeline() {
        ResponseEntity<?> resp = controller.evaluate(request(""), http);
        assertEquals(200, resp.getStatusCode().value());
        verify(orchestrator).evaluate(any());
        verify(outputReview, never()).review(any());
    }

    @Test
    void nullRoleDefaultsToToolCallPipeline() {
        assertEquals(200, controller.evaluate(request(null), http).getStatusCode().value());
        verify(orchestrator).evaluate(any());
    }

    @Test
    void toolCallRoleUsesTheOrchestrator() {
        assertEquals(200, controller.evaluate(request("tool_call"), http).getStatusCode().value());
        verify(orchestrator).evaluate(any());
        verify(outputReview, never()).review(any());
    }

    @Test
    void inputRoleUsesTheOrchestratorNotOutputReview() {
        assertEquals(200, controller.evaluate(request("input"), http).getStatusCode().value());
        verify(orchestrator).evaluate(any());
        verify(outputReview, never()).review(any());
    }

    @Test
    void outputRoleRoutesToOutputReview() {
        assertEquals(200, controller.evaluate(request("output"), http).getStatusCode().value());
        verify(outputReview).review(any());
        verify(orchestrator, never()).evaluate(any());
    }
}

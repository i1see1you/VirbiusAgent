package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.virbius.engine.auth.EvaluateAuthFilter;
import io.virbius.engine.auth.TrustedIssuerRegistry.IssuedTokenClaims;
import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class EvaluateHttpControllerAuthTest {

    @Mock
    private EvaluateOrchestrator orchestrator;

    @Mock
    private OutputReviewService outputReview;

    private EvaluateHttpController controller;

    @BeforeEach
    void setUp() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 10_000, 100_000, 10_000);
        controller = new EvaluateHttpController(orchestrator, outputReview, limiter);
        lenient().when(orchestrator.evaluate(any())).thenReturn(
                new EvaluateResponseDto("allow", 0, 0, "POLICY_ALLOW", 0,
                        "POLICY_ALLOW", "tr", false, "full", null, null));
        lenient().when(outputReview.review(any())).thenReturn(
                new EvaluateResponseDto("allow", 0, 0, "POLICY_ALLOW", 0,
                        "POLICY_ALLOW", "tr", false, "full", null, null, "warn", null));
    }

    private static IssuedTokenClaims agent(String tenant, String app, List<String> tools, Integer quota) {
        return new IssuedTokenClaims("test-kid", "control", "agent:aid-1", "evaluate",
                tenant, app, "aid-1", null, "jti-1", false, tools, quota, 60,
                System.currentTimeMillis() / 1000, System.currentTimeMillis() / 1000 + 900);
    }

    private static IssuedTokenClaims service(String purpose) {
        return new IssuedTokenClaims("test-kid", "control", "service:" + purpose, "evaluate",
                null, null, null, null, "jti-2", true, List.of("*"), null, null,
                System.currentTimeMillis() / 1000, System.currentTimeMillis() / 1000 + 900);
    }

    private static MockHttpServletRequest reqWith(IssuedTokenClaims subject) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("127.0.0.1");
        if (subject != null) {
            r.setAttribute(EvaluateAuthFilter.SUBJECT_ATTRIBUTE, subject);
        }
        return r;
    }

    private static EvaluateRequestDto request(
            String tenant, String role, String tool, String session, int quota) {
        return new EvaluateRequestDto(
                tenant, role, session, "hello", false, null, "tr-1", "u1", null,
                Map.of("app_id", "app-1"), null, null, null, null, tool, null, quota);
    }

    @Test
    void tenantMismatchIsRejected() {
        ResponseEntity<?> resp = controller.evaluate(
                request("other-tenant", "output", "chat_completion", "s1", 100),
                reqWith(agent("default", "app-1", List.of("read_file"), 80)));
        assertEquals(403, resp.getStatusCode().value());
        assertEquals("subject_mismatch", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void tenantFromTokenWinsWhenRequestOmitted() {
        ArgumentCaptor<EvaluateRequestDto> captor = ArgumentCaptor.forClass(EvaluateRequestDto.class);
        controller.evaluate(
                new EvaluateRequestDto("", "output", "s1", "x", false, null, "tr", null, null,
                        Map.of(), null, null, null, null, "chat_completion", null, 100),
                reqWith(agent("default", "app-1", List.of("read_file"), 80)));
        org.mockito.Mockito.verify(outputReview).review(captor.capture());
        assertEquals("default", captor.getValue().tenantId());
    }

    @Test
    void capabilityDeniedForToolCallNotInScope() {
        ResponseEntity<?> resp = controller.evaluate(
                request("default", "tool_call", "curl", "s1", 100),
                reqWith(agent("default", "app-1", List.of("read_file"), 80)));
        assertEquals(403, resp.getStatusCode().value());
        assertEquals("capability_denied", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void inputRoleIsExemptFromCapabilityCheckB2() {
        // chat_input is not an MCP tool -> must NOT be capability-denied.
        ResponseEntity<?> resp = controller.evaluate(
                request("default", "input", "chat_input", "s1", 100),
                reqWith(agent("default", "app-1", List.of("read_file"), 80)));
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void wildcardScopeAllowsAnyTool() {
        ResponseEntity<?> resp = controller.evaluate(
                request("default", "tool_call", "anything", "s1", 100),
                reqWith(agent("default", "app-1", List.of("*"), 80)));
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void serviceTokenMayCrossTenants() {
        ResponseEntity<?> resp = controller.evaluate(
                request("tenant-x", "output", "chat_completion", "s1", 100),
                reqWith(service("control-simulate")));
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void riskQuotaIsClampedToTokenScope() {
        ArgumentCaptor<EvaluateRequestDto> captor = ArgumentCaptor.forClass(EvaluateRequestDto.class);
        controller.evaluate(
                request("default", "tool_call", "read_file", "s1", 100),
                reqWith(agent("default", "app-1", List.of("read_file"), 80)));
        org.mockito.Mockito.verify(orchestrator).evaluate(captor.capture());
        assertEquals(80, captor.getValue().riskQuota());
    }

    @Test
    void sidBindingMismatchIsRejected() {
        MockHttpServletRequest http = reqWith(agent("default", "app-1", List.of("read_file"), 80));
        http.setAttribute("virbius.required-sid", "s-required");
        ResponseEntity<?> resp = controller.evaluate(
                request("default", "output", "chat_completion", "s-other", 100), http);
        assertEquals(401, resp.getStatusCode().value());
        assertEquals("sid_mismatch", ((Map<?, ?>) resp.getBody()).get("reason"));
    }

    @Test
    void noSubjectBehavesAsLegacy() {
        // No verified subject (auth disabled / internal call) -> unchanged behaviour.
        ResponseEntity<?> resp = controller.evaluate(
                request("default", "output", "chat_completion", "s1", 100), reqWith(null));
        assertEquals(200, resp.getStatusCode().value());
    }
}

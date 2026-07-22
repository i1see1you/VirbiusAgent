package io.virbius.engine.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ChallengeControllerTest {

    @Mock
    private ChallengeService challengeService;

    private ChallengeController controller;

    @BeforeEach
    void setUp() {
        controller = new ChallengeController(challengeService);
    }

    @Test
    void getStatusReturns200() {
        Map<String, Object> record = Map.of(
                "challenge_id", "ch_abc123",
                "status", "pending",
                "risk_score", 75);
        when(challengeService.getStatus("ch_abc123")).thenReturn(record);

        ResponseEntity<Map<String, Object>> resp = controller.getStatus("ch_abc123");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("pending", resp.getBody().get("status"));
    }

    @Test
    void getStatusReturns404() {
        when(challengeService.getStatus("ch_missing"))
                .thenReturn(Map.of("status", "not_found", "challenge_id", "ch_missing"));

        ResponseEntity<Map<String, Object>> resp = controller.getStatus("ch_missing");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void approveReturns200() {
        Map<String, Object> result = Map.of(
                "challenge_id", "ch_abc",
                "status", "approved",
                "token", "vct_xxx",
                "expires_at", 999999L);
        when(challengeService.approve("ch_abc", "operator", "ok"))
                .thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                "ch_abc", Map.of("approved_by", "operator", "comment", "ok"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("approved", resp.getBody().get("status"));
    }

    @Test
    void approveReturns404() {
        when(challengeService.approve("ch_missing", "op", ""))
                .thenReturn(Map.of("status", "not_found"));

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                "ch_missing", Map.of("approved_by", "op"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void rejectReturns200() {
        when(challengeService.reject("ch_abc", "operator", "no reason"))
                .thenReturn(Map.of("challenge_id", "ch_abc", "status", "rejected"));

        ResponseEntity<Map<String, Object>> resp = controller.reject(
                "ch_abc", Map.of("rejected_by", "operator", "reason", "no reason"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("rejected", resp.getBody().get("status"));
    }

    @Test
    void rejectReturns404() {
        when(challengeService.reject("ch_missing", "op", ""))
                .thenReturn(Map.of("status", "not_found"));

        ResponseEntity<Map<String, Object>> resp = controller.reject(
                "ch_missing", Map.of("rejected_by", "op"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void verifyTokenValidReturns200() {
        Map<String, Object> result = Map.of(
                "valid", true,
                "challenge_id", "ch_abc",
                "approved_by", "op");
        when(challengeService.verifyToken("vct_good", "tool", "hash", "sess"))
                .thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.verifyToken(
                Map.of("token", "vct_good", "tool_name", "tool",
                        "args_hash", "hash", "session_id", "sess"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void verifyTokenInvalidReturns403() {
        when(challengeService.verifyToken("vct_bad", "tool", "hash", "sess"))
                .thenReturn(Map.of("valid", false, "reason", "token_not_found"));

        ResponseEntity<Map<String, Object>> resp = controller.verifyToken(
                Map.of("token", "vct_bad", "tool_name", "tool",
                        "args_hash", "hash", "session_id", "sess"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void listChallengesReturnsList() {
        List<Map<String, Object>> challenges = List.of(
                Map.of("challenge_id", "ch_1", "status", "pending"),
                Map.of("challenge_id", "ch_2", "status", "pending"));
        when(challengeService.listChallenges("default", "pending", 50))
                .thenReturn(challenges);

        ResponseEntity<List<Map<String, Object>>> resp = controller.listChallenges("default", "pending", 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }

    @Test
    void listChallengesWithDefaults() {
        when(challengeService.listChallenges("default", null, 50))
                .thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> resp = controller.listChallenges("default", null, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void approveUsesDefaultsWhenBodyMissing() {
        when(challengeService.approve("ch_abc", "unknown", ""))
                .thenReturn(Map.of("status", "approved", "token", "vct_x"));

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                "ch_abc", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void rejectUsesDefaultsWhenBodyMissing() {
        when(challengeService.reject("ch_abc", "unknown", ""))
                .thenReturn(Map.of("status", "rejected"));

        ResponseEntity<Map<String, Object>> resp = controller.reject(
                "ch_abc", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}

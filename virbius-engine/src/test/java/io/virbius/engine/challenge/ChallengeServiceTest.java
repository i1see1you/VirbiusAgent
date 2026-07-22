package io.virbius.engine.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class ChallengeServiceTest {

    private ObjectMapper mapper;
    private ChallengeService service;
    private ChallengeService serviceNoRedis;
    private JedisPool jedisPool;
    private Jedis jedis;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        service = new ChallengeService(
                Optional.of(jedisPool), mapper, 600, 600, 600);

        serviceNoRedis = new ChallengeService(
                Optional.empty(), mapper, 600, 600, 600);
    }

    // ── createChallenge ────────────────────────────────────────────────

    @Test
    void createChallengeWithRedis() throws Exception {
        String tenantId = "tenant-1";
        String sessionId = "sess-1";
        String toolName = "read_file";
        String argsHash = "sha256:abc";
        String ruleId = "Rule_101";
        String reasonCode = "TOOL_HIGH_RISK";
        int riskScore = 75;

        String challengeId = service.createChallenge(tenantId, sessionId, toolName,
                argsHash, ruleId, reasonCode, riskScore);

        assertNotNull(challengeId);
        assertTrue(challengeId.startsWith("ch_"));

        verify(jedis).setex(anyString(), eq(600L), anyString());
        verify(jedis).zadd(anyString(), anyDouble(), eq(challengeId));
    }

    @Test
    void createChallengeWithoutRedis() {
        String challengeId = serviceNoRedis.createChallenge(
                "t1", "s1", "tool", "h1", "R1", "code", 50);

        assertNotNull(challengeId);
        assertTrue(challengeId.startsWith("ch_"));
    }

    // ── getStatus ──────────────────────────────────────────────────────

    @Test
    void getStatusNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        Map<String, Object> result = service.getStatus("ch_nonexistent");

        assertEquals("not_found", result.get("status"));
    }

    @Test
    void getStatusPending() throws Exception {
        String challengeId = createPendingRecord();

        Map<String, Object> result = service.getStatus(challengeId);

        assertEquals("pending", result.get("status"));
        assertNull(result.get("token")); // token removed for non-approved
    }

    // ── approve ────────────────────────────────────────────────────────

    @Test
    void approveNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        Map<String, Object> result = service.approve("ch_nonexistent", "op1", "ok");

        assertEquals("not_found", result.get("status"));
    }

    @Test
    void approveSuccessfully() throws Exception {
        String challengeId = createPendingRecord();

        Map<String, Object> result = service.approve(challengeId, "operator", "looks good");

        assertEquals("approved", result.get("status"));
        assertNotNull(result.get("token"));
        assertTrue(((String) result.get("token")).startsWith("vct_"));
        assertNotNull(result.get("expires_at"));
    }

    @Test
    void approveAlreadyApproved() throws Exception {
        String challengeId = createPendingRecord();
        service.approve(challengeId, "op1", "ok");

        Map<String, Object> result = service.approve(challengeId, "op2", "again");

        assertEquals("approved", result.get("status"));
        assertEquals(challengeId, result.get("challenge_id"));
    }

    @Test
    void approveWithoutRedis() {
        // serviceNoRedis creates and approves without Redis
        String challengeId = serviceNoRedis.createChallenge(
                "t1", "s1", "tool", "h1", "R1", "code", 50);

        Map<String, Object> result = serviceNoRedis.approve(challengeId, "op1", "comment");

        assertEquals("not_found", result.get("status"));
    }

    // ── reject ─────────────────────────────────────────────────────────

    @Test
    void rejectNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        Map<String, Object> result = service.reject("ch_nonexistent", "op1", "bad");

        assertEquals("not_found", result.get("status"));
    }

    @Test
    void rejectSuccessfully() throws Exception {
        String challengeId = createPendingRecord();

        Map<String, Object> result = service.reject(challengeId, "operator", "not appropriate");

        assertEquals("rejected", result.get("status"));
        verify(jedis).zrem(anyString(), eq(challengeId));
    }

    @Test
    void rejectAlreadyApproved() throws Exception {
        String challengeId = createApprovedRecord();

        Map<String, Object> result = service.reject(challengeId, "op2", "no");

        assertEquals("approved", result.get("status"));
    }

    // ── verifyToken ────────────────────────────────────────────────────

    @Test
    void verifyTokenEmpty() {
        Map<String, Object> result = service.verifyToken("", "tool", "hash", "sess");

        assertFalse((Boolean) result.get("valid"));
        assertEquals("empty_token", result.get("reason"));
    }

    @Test
    void verifyTokenNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        Map<String, Object> result = service.verifyToken("vct_bad", "tool", "hash", "sess");

        assertFalse((Boolean) result.get("valid"));
        assertEquals("token_not_found_or_expired", result.get("reason"));
    }

    @Test
    void verifyTokenSuccess() throws Exception {
        String token = "vct_good_token";
        String challengeId = "ch_abc123";
        String tokenJson = mapper.writeValueAsString(Map.of(
                "challenge_id", challengeId,
                "used", false,
                "tool_name", "read_file",
                "args_hash", "sha256:abc",
                "session_id", "sess-1",
                "approved_by", "op1"));
        String updatedJson = mapper.writeValueAsString(Map.of(
                "challenge_id", challengeId,
                "used", true,
                "tool_name", "read_file",
                "args_hash", "sha256:abc",
                "session_id", "sess-1",
                "approved_by", "op1"));

        when(jedis.get("challenge:token:" + token)).thenReturn(tokenJson);
        when(jedis.getSet("challenge:token:" + token, updatedJson)).thenReturn(tokenJson);

        Map<String, Object> result = service.verifyToken(token, "read_file", "sha256:abc", "sess-1");

        assertTrue((Boolean) result.get("valid"));
        assertEquals(challengeId, result.get("challenge_id"));
    }

    @Test
    void verifyTokenToolMismatch() throws Exception {
        String token = "vct_tool_mismatch";
        String tokenJson = mapper.writeValueAsString(Map.of(
                "challenge_id", "ch_1",
                "used", false,
                "tool_name", "read_file",
                "args_hash", "sha256:abc",
                "session_id", "sess-1",
                "approved_by", "op1"));

        when(jedis.get("challenge:token:" + token)).thenReturn(tokenJson);

        Map<String, Object> result = service.verifyToken(token, "wrong_tool", "sha256:abc", "sess-1");

        assertFalse((Boolean) result.get("valid"));
        assertEquals("tool_name_mismatch", result.get("reason"));
    }

    @Test
    void verifyTokenSessionMismatch() throws Exception {
        String token = "vct_sess_mismatch";
        String tokenJson = mapper.writeValueAsString(Map.of(
                "challenge_id", "ch_1",
                "used", false,
                "tool_name", "read_file",
                "args_hash", "sha256:abc",
                "session_id", "sess-1",
                "approved_by", "op1"));

        when(jedis.get("challenge:token:" + token)).thenReturn(tokenJson);

        Map<String, Object> result = service.verifyToken(token, "read_file", "sha256:abc", "wrong_sess");

        assertFalse((Boolean) result.get("valid"));
        assertEquals("session_id_mismatch", result.get("reason"));
    }

    // ── listChallenges ─────────────────────────────────────────────────

    @Test
    void listChallengesEmptyWithoutRedis() {
        var result = serviceNoRedis.listChallenges("default", "pending", 50);
        assertTrue(result.isEmpty());
    }

    @Test
    void listChallengesWithStatusFilter() throws Exception {
        String id1 = createPendingRecord();
        String id2 = createPendingRecord();

        when(jedis.zrevrange("challenge:queue:default", 0, 49))
                .thenReturn(java.util.List.of(id1, id2));

        var result = service.listChallenges("default", "pending", 50);

        assertEquals(2, result.size());
        assertEquals("pending", result.get(0).get("status"));
    }

    // ── hasActiveExemption ─────────────────────────────────────────────

    @Test
    void hasActiveExemptionReturnsTrue() {
        when(jedis.exists(anyString())).thenReturn(true);

        boolean exempt = service.hasActiveExemption("sess-1", "read_file", "sha256:abc");

        assertTrue(exempt);
    }

    @Test
    void hasActiveExemptionReturnsFalse() {
        when(jedis.exists(anyString())).thenReturn(false);

        boolean exempt = service.hasActiveExemption("sess-1", "read_file", "sha256:abc");

        assertFalse(exempt);
    }

    @Test
    void hasActiveExemptionWithoutRedis() {
        boolean exempt = serviceNoRedis.hasActiveExemption("sess-1", "read_file", "sha256:abc");
        assertFalse(exempt);
    }

    // ── computeArgsHash ────────────────────────────────────────────────

    @Test
    void computeArgsHashProducesSha256Prefix() {
        String hash = ChallengeService.computeArgsHash("read_file", "{\"path\":\"/tmp\"}");
        assertTrue(hash.startsWith("sha256:"));
        assertEquals(64 + 7, hash.length()); // "sha256:" + 64 hex chars
    }

    @Test
    void computeArgsHashDeterministic() {
        String h1 = ChallengeService.computeArgsHash("tool", "{\"a\":1}");
        String h2 = ChallengeService.computeArgsHash("tool", "{\"a\":1}");
        assertEquals(h1, h2);
    }

    @Test
    void computeArgsHashDifferentToolDifferentHash() {
        String h1 = ChallengeService.computeArgsHash("tool_a", "{\"a\":1}");
        String h2 = ChallengeService.computeArgsHash("tool_b", "{\"a\":1}");
        assertFalse(h1.equals(h2));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private String createApprovedRecord() throws Exception {
        String challengeId = "ch_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("challenge_id", challengeId);
        record.put("status", "approved");
        record.put("tenant_id", "default");
        record.put("session_id", "sess-1");
        record.put("tool_name", "read_file");
        record.put("args_hash", "sha256:abc");
        record.put("rule_id", "Rule_101");
        record.put("reason_code", "TOOL_HIGH_RISK");
        record.put("risk_score", 75);
        record.put("created_at", System.currentTimeMillis() / 1000);
        record.put("expires_at", (System.currentTimeMillis() / 1000) + 600);
        record.put("approved_by", "op1");
        record.put("approved_at", System.currentTimeMillis() / 1000);
        record.put("token", null);

        when(jedis.get("challenge:" + challengeId)).thenReturn(mapper.writeValueAsString(record));
        return challengeId;
    }

    private String createPendingRecord() throws Exception {
        String challengeId = "ch_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("challenge_id", challengeId);
        record.put("status", "pending");
        record.put("tenant_id", "default");
        record.put("session_id", "sess-1");
        record.put("tool_name", "read_file");
        record.put("args_hash", "sha256:abc");
        record.put("rule_id", "Rule_101");
        record.put("reason_code", "TOOL_HIGH_RISK");
        record.put("risk_score", 75);
        record.put("created_at", System.currentTimeMillis() / 1000);
        record.put("expires_at", (System.currentTimeMillis() / 1000) + 600);
        record.put("approved_by", null);
        record.put("approved_at", null);
        record.put("token", null);

        when(jedis.get("challenge:" + challengeId)).thenReturn(mapper.writeValueAsString(record));
        return challengeId;
    }
}

package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyPolicyServiceTest {

    @Test
    void defaultsMatchProxyToml() {
        Map<String, Object> policy = ProxyPolicyService.defaults();
        assertEquals(1800, policy.get("session_ttl_secs"));
        assertEquals("minimum_privilege", policy.get("fallback_policy"));
        assertTrue(((List<?>) policy.get("upstreams")).isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) policy.get("memory");
        assertEquals(false, memory.get("enabled"));
    }

    @Test
    void rejectsUnknownFallback() {
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> ProxyPolicyService.normalize(Map.of("fallback_policy", "allow_all")));
        assertEquals(400, ex.getCode());
    }

    @Test
    void keepsUpstreamAndDenyPolicy() {
        Map<String, Object> policy = ProxyPolicyService.normalize(Map.of(
                "fallback_policy", "default_deny",
                "session_ttl_secs", 60,
                "upstreams", List.of(Map.of("name", "tools", "url", "http://mcp:9000"))));
        assertEquals("default_deny", policy.get("fallback_policy"));
        assertEquals(60, policy.get("session_ttl_secs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upstreams = (List<Map<String, Object>>) policy.get("upstreams");
        assertEquals("/sse", upstreams.get(0).get("sse_path"));
        assertEquals("http://mcp:9000", upstreams.get(0).get("url"));
    }
}

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
    void failoverDefaultsFailClosedWithTenSecondTimeout() {
        Map<String, Object> policy = ProxyPolicyService.normalize(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> failover = (Map<String, Object>) policy.get("failover");
        assertEquals(true, failover.get("high_risk_fail_closed"));
        assertEquals(false, failover.get("low_risk_fail_open"));
        assertEquals(10000, failover.get("engine_timeout_ms"));
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

    @Test
    void keepsTransportAndPaths() {
        Map<String, Object> policy = ProxyPolicyService.normalize(Map.of(
                "upstreams", List.of(Map.of(
                        "name", "remote",
                        "transport", "streamable-http",
                        "url", "http://mcp:9000",
                        "http_path", "/mcp",
                        "timeout_ms", 45000))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upstreams = (List<Map<String, Object>>) policy.get("upstreams");
        assertEquals("streamable-http", upstreams.get(0).get("transport"));
        assertEquals("/mcp", upstreams.get(0).get("http_path"));
        assertEquals("/sse", upstreams.get(0).get("sse_path"));
        assertEquals(45000, upstreams.get(0).get("timeout_ms"));
    }

    @Test
    void defaultsTransportToAuto() {
        Map<String, Object> policy = ProxyPolicyService.normalize(Map.of(
                "upstreams", List.of(Map.of("name", "tools", "url", "http://mcp:9000"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upstreams = (List<Map<String, Object>>) policy.get("upstreams");
        assertEquals("auto", upstreams.get(0).get("transport"));
    }

    @Test
    void stdioUpstreamRequiresCommandAndRejectsUrl() {
        Map<String, Object> ok = ProxyPolicyService.normalize(Map.of(
                "upstreams", List.of(Map.of(
                        "name", "fs",
                        "transport", "stdio",
                        "command", "npx",
                        "args", List.of("-y", "server-filesystem"),
                        "env", Map.of("FS_ROOT", "/data")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upstreams = (List<Map<String, Object>>) ok.get("upstreams");
        assertEquals("npx", upstreams.get(0).get("command"));
        assertEquals(List.of("-y", "server-filesystem"), upstreams.get(0).get("args"));
        assertEquals(Map.of("FS_ROOT", "/data"), upstreams.get(0).get("env"));

        BusinessException noCommand = assertThrows(
                BusinessException.class,
                () -> ProxyPolicyService.normalize(Map.of(
                        "upstreams", List.of(Map.of("name", "fs", "transport", "stdio")))));
        assertEquals(400, noCommand.getCode());

        BusinessException withUrl = assertThrows(
                BusinessException.class,
                () -> ProxyPolicyService.normalize(Map.of(
                        "upstreams", List.of(Map.of(
                                "name", "fs",
                                "transport", "stdio",
                                "command", "npx",
                                "url", "http://mcp:9000")))));
        assertEquals(400, withUrl.getCode());
    }

    @Test
    void rejectsUpstreamHeadersFromConsole() {
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> ProxyPolicyService.normalize(Map.of(
                        "upstreams", List.of(Map.of(
                                "name", "remote",
                                "url", "http://mcp:9000",
                                "headers", Map.of("Authorization", "Bearer x"))))));
        assertEquals(400, ex.getCode());
    }

    @Test
    void rejectsUnknownTransport() {
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> ProxyPolicyService.normalize(Map.of(
                        "upstreams", List.of(Map.of(
                                "name", "remote",
                                "transport", "websocket",
                                "url", "http://mcp:9000")))));
        assertEquals(400, ex.getCode());
    }
}

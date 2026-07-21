package io.virbius.policy;

import java.util.Collections;
import java.util.Map;

/** Request fields used to resolve rule {@code value} and {@link BindScope} matching. */
public record MatchContext(
        String content,
        String userId,
        String deviceId,
        String clientIp,
        String sessionId,
        Map<String, String> vars,
        Map<String, String> query,
        Map<String, String> headers,
        String routeUri,
        String upstreamId,
        String consumerId,
        String apiKeyGroup,
        String toolName) {

    public MatchContext {
        vars = vars != null ? Map.copyOf(vars) : Map.of();
        query = query != null ? Map.copyOf(query) : Map.of();
        headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    public static MatchContext of(
            String content,
            String userId,
            String deviceId,
            String clientIp,
            String sessionId,
            Map<String, String> vars) {
        return new MatchContext(
                content, userId, deviceId, clientIp, sessionId, vars, Map.of(), Map.of(), null, null, null, null, null);
    }

    public static MatchContext withBind(
            String content,
            String userId,
            String deviceId,
            String clientIp,
            String sessionId,
            Map<String, String> vars,
            String routeUri) {
        return new MatchContext(
                content,
                userId,
                deviceId,
                clientIp,
                sessionId,
                vars,
                Map.of(),
                Map.of(),
                routeUri,
                null,
                null,
                null,
                null);
    }

    public static MatchContext forToolCall(
            String content,
            String userId,
            String deviceId,
            String clientIp,
            String sessionId,
            Map<String, String> vars,
            String toolName) {
        return new MatchContext(
                content, userId, deviceId, clientIp, sessionId, vars, Map.of(), Map.of(), null, null, null, null, toolName);
    }

    /**
     * Factory for tool-call evaluation that also preserves {@code routeUri}.
     *
     * <p>Used by {@code EvaluateOrchestrator} when the Engine receives a
     * tool-call evaluation request that carries both a {@code routeUri}
     * (gateway-originated requests) and a {@code toolName} (MCP Proxy requests).
     * This ensures {@code bind_scope=tool} rules can match via
     * {@link BindScope#matchesTool} while keeping {@code routeUri} available
     * for gateway-compatible rule matching.
     */
    public static MatchContext forToolCallWithRoute(
            String content,
            String userId,
            String deviceId,
            String clientIp,
            String sessionId,
            Map<String, String> vars,
            String routeUri,
            String toolName) {
        return new MatchContext(
                content, userId, deviceId, clientIp, sessionId, vars, Map.of(), Map.of(),
                routeUri, null, null, null, toolName);
    }

    public Map<String, String> varsOrEmpty() {
        return vars != null ? vars : Collections.emptyMap();
    }
}

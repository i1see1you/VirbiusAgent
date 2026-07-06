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
        String scene,
        String routeUri,
        String upstreamId,
        String consumerId,
        String apiKeyGroup) {

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
            String scene,
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
                scene,
                routeUri,
                null,
                null,
                null);
    }

    public Map<String, String> varsOrEmpty() {
        return vars != null ? vars : Collections.emptyMap();
    }
}

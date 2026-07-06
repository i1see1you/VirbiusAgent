package io.virbius.engine.eval;

import java.util.List;
import java.util.Map;

public record EvaluateRequestDto(
        String tenantId,
        String scene,
        String role,
        String sessionId,
        String content,
        boolean streamChunk,
        List<SignalDto> priorSignals,
        String traceId,
        String userId,
        String deviceId,
        Map<String, String> vars,
        String routeUri,
        String upstreamId,
        String consumerId,
        String apiKeyGroup) {}

package io.virbius.engine.grpc;

import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import io.virbius.engine.eval.SignalDto;
import io.virbius.engine.v1.EvaluateRequest;
import io.virbius.engine.v1.EvaluateResponse;
import io.virbius.engine.v1.Signal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProtoMapper {

    public EvaluateRequestDto toDto(EvaluateRequest proto) {
        List<SignalDto> priorSignals = new ArrayList<>();
        for (Signal s : proto.getPriorSignalsList()) {
            priorSignals.add(toDto(s));
        }
        return new EvaluateRequestDto(
                proto.getTenantId(),
                proto.getScene(),
                proto.getRole(),
                proto.getSessionId(),
                proto.getContent(),
                proto.getIsStreamChunk(),
                priorSignals,
                proto.getTraceId(),
                proto.getUserId(),
                proto.getDeviceId(),
                proto.getVarsMap(),
                null,
                null,
                null,
                null);
    }

    public EvaluateResponse toProto(EvaluateResponseDto dto) {
        return EvaluateResponse.newBuilder()
                .setEffectiveAction(dto.effectiveAction())
                .setMaxRiskScore(dto.maxRiskScore())
                .setRuleId(dto.ruleId())
                .setRuleRevision(dto.ruleRevision())
                .setReasonCode(dto.reasonCode())
                .setTraceId(dto.traceId())
                .setDegraded(dto.degraded())
                .build();
    }

    public SignalDto toDto(Signal proto) {
        return new SignalDto(
                proto.getRuleId(),
                proto.getRuleRevision(),
                proto.getSource(),
                proto.getLayer(),
                proto.getScore(),
                proto.getReasonCode(),
                proto.getIntentAction(),
                proto.getEnforceMode(),
                proto.hasCanaryPercent() ? proto.getCanaryPercent() : null,
                null);
    }
}

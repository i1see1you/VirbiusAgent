package io.virbius.engine.api;

import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP mirror of the gateway-agent contract, for local debugging; production path is gRPC :50051. */
@RestController
public class EvaluateHttpController {

    private final EvaluateOrchestrator orchestrator;

    public EvaluateHttpController(EvaluateOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/v1/evaluate")
    public EvaluateResponseDto evaluate(@RequestBody EvaluateRequestDto request) {
        return orchestrator.evaluate(request);
    }
}

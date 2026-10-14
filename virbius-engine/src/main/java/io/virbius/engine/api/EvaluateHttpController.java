package io.virbius.engine.api;

import io.virbius.engine.eval.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authoritative internal decision API (HTTP/JSON). This is the single decision
 * path consumed by the MCP proxy, the gateway (compiled {@code engine_url}),
 * and the Control-side simulate/challenge facades. The former gRPC mirror
 * (port 50051) was removed — see CHANGELOG.
 */
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

    /** P1.2: STI Taint detection endpoint for tool return values. */
    @PostMapping("/v1/evaluate/tool-result")
    public ToolResultResponseDto evaluateToolResult(@RequestBody ToolResultRequestDto request) {
        return orchestrator.evaluateToolResult(request);
    }

    /** P1.3: LLM-based injection detection endpoint for memory writes. */
    @PostMapping("/v1/memory/check")
    public MemoryCheckResponseDto checkMemory(@RequestBody MemoryCheckRequestDto request) {
        return orchestrator.checkMemory(request);
    }
}

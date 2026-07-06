package io.virbius.engine.api;

import io.virbius.engine.eval.PromptSimulateRequestDto;
import io.virbius.engine.eval.PromptSimulateResponseDto;
import io.virbius.engine.eval.PromptSimulateService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PromptSimulateHttpController {

    private final PromptSimulateService simulateService;

    public PromptSimulateHttpController(PromptSimulateService simulateService) {
        this.simulateService = simulateService;
    }

    @PostMapping("/v1/simulate/prompt")
    public PromptSimulateResponseDto simulate(@RequestBody PromptSimulateRequestDto request) {
        return simulateService.simulate(request);
    }
}

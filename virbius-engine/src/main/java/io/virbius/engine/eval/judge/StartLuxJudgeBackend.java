package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.virbius.engine.config.PromptJudgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class StartLuxJudgeBackend extends AbstractHttpJudgeBackend {

    public static final String ID = "startlux-4b";

    StartLuxJudgeBackend(
            PromptJudgeProperties properties,
            ObjectMapper mapper,
            MeterRegistry registry,
            RestClient client) {
        super(ID, properties, mapper, registry, client);
    }

    @Autowired
    public StartLuxJudgeBackend(
            PromptJudgeProperties properties, ObjectMapper mapper, MeterRegistry registry) {
        super(ID, properties, mapper, registry);
    }

    @Override
    public JudgeCapabilities capabilities() {
        // The StartLux gguf server answers multiple questions per request but
        // runs one forward pass per question: batching saves HTTP round trips only.
        return new JudgeCapabilities(8, false, 2048);
    }
}

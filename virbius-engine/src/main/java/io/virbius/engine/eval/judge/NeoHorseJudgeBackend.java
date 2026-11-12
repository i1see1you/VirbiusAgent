package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.virbius.engine.config.PromptJudgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NeoHorseJudgeBackend extends AbstractHttpJudgeBackend {

    public static final String ID = "neohorse-4b";

    NeoHorseJudgeBackend(
            PromptJudgeProperties properties,
            ObjectMapper mapper,
            MeterRegistry registry,
            RestClient client) {
        super(ID, properties, mapper, registry, client);
    }

    @Autowired
    public NeoHorseJudgeBackend(
            PromptJudgeProperties properties, ObjectMapper mapper, MeterRegistry registry) {
        super(ID, properties, mapper, registry);
    }

    @Override
    public JudgeCapabilities capabilities() {
        return new JudgeCapabilities(16, true, 2048);
    }
}

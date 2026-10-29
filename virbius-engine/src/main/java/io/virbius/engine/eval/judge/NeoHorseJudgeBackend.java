package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptJudgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NeoHorseJudgeBackend extends AbstractHttpJudgeBackend {

    public static final String ID = "neohorse-4b";

    NeoHorseJudgeBackend(String baseUrl, ObjectMapper mapper, RestClient client) {
        super(ID, baseUrl, mapper, false, client);
    }

    @Autowired
    public NeoHorseJudgeBackend(PromptJudgeProperties properties, ObjectMapper mapper) {
        super(ID, properties, mapper, false);
    }

    @Override
    protected String judgePath() {
        return "/v1/judge";
    }

    @Override
    public JudgeCapabilities capabilities() {
        return new JudgeCapabilities(true, false, false, 0, 1, "Apache-2.0", true);
    }
}

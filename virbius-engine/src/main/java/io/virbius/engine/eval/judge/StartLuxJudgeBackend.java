package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptJudgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class StartLuxJudgeBackend extends AbstractHttpJudgeBackend {

    public static final String ID = "startlux-4b";

    StartLuxJudgeBackend(String baseUrl, ObjectMapper mapper, RestClient client) {
        super(ID, baseUrl, mapper, true, client);
    }

    @Autowired
    public StartLuxJudgeBackend(PromptJudgeProperties properties, ObjectMapper mapper) {
        super(ID, properties, mapper, true);
    }

    @Override
    protected String judgePath() {
        return "/v1/systemone";
    }

    @Override
    public JudgeCapabilities capabilities() {
        return new JudgeCapabilities(true, true, true, 65536, 16, "CC-BY-NC-4.0", false);
    }
}

package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptJudgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LayaJudgeBackend extends AbstractHttpJudgeBackend {

    public static final String ID = "laya-zh";

    LayaJudgeBackend(String baseUrl, ObjectMapper mapper, RestClient client) {
        super(ID, baseUrl, mapper, false, client);
    }

    @Autowired
    public LayaJudgeBackend(PromptJudgeProperties properties, ObjectMapper mapper) {
        super(ID, properties, mapper, false);
    }

    @Override
    protected String judgePath() {
        return "/v1/judge";
    }

    @Override
    public JudgeCapabilities capabilities() {
        return new JudgeCapabilities(true, false, false, 0, 1, "unknown", false);
    }
}

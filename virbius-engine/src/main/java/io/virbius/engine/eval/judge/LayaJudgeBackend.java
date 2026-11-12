package io.virbius.engine.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.virbius.engine.config.PromptJudgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Laya decision model (Apache-2.0 family): points at the multilingual checkpoint
 * (mmBERT-base, 1024-token default context, 100+ languages incl. Chinese) via
 * {@code model: laya-multilingual}; the English root checkpoint cannot read
 * non-Latin scripts. Internal canary / low-resource backend only - not a
 * product-facing capability (owner decision 2026-11-11).
 */
@Component
public class LayaJudgeBackend extends AbstractHttpJudgeBackend {

    public static final String ID = "laya";

    LayaJudgeBackend(
            PromptJudgeProperties properties,
            ObjectMapper mapper,
            MeterRegistry registry,
            RestClient client) {
        super(ID, properties, mapper, registry, client);
    }

    @Autowired
    public LayaJudgeBackend(
            PromptJudgeProperties properties, ObjectMapper mapper, MeterRegistry registry) {
        super(ID, properties, mapper, registry);
    }

    @Override
    public JudgeCapabilities capabilities() {
        return new JudgeCapabilities(1, false, 1024);
    }
}

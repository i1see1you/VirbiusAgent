package io.virbius.engine.eval.judge;

import io.virbius.engine.config.PromptJudgeProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PromptJudgeBackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptJudgeBackendRegistry.class);

    private final PromptJudgeProperties properties;
    private final Map<String, PromptJudgeBackend> backends;

    public PromptJudgeBackendRegistry(
            List<PromptJudgeBackend> backends, PromptJudgeProperties properties) {
        this.properties = properties;
        Map<String, PromptJudgeBackend> byId = new LinkedHashMap<>();
        for (PromptJudgeBackend backend : backends) {
            if (byId.putIfAbsent(backend.id(), backend) != null) {
                throw new IllegalStateException("duplicate prompt judge backend: " + backend.id());
            }
        }
        this.backends = Map.copyOf(byId);
        properties.backends().forEach((id, config) -> {
            if (config.enabled() && (config.model() == null || config.model().isBlank())) {
                log.warn("prompt judge backend {} is enabled without a model; "
                        + "judge requests will fail until it is configured", id);
            }
            if (config.enabled() && !byId.containsKey(id)) {
                log.warn("prompt judge backend {} is enabled but no implementation is registered; "
                        + "judge rules bound to it will fall to on_error until the id is corrected", id);
            }
        });
        byId.keySet().forEach(id -> {
            if (!properties.backends().containsKey(id)) {
                log.warn("prompt judge backend {} is registered but has no configuration entry; "
                        + "it can never be enabled", id);
            }
        });
        String defaultId = properties.defaultBackend();
        PromptJudgeProperties.Backend defaultConfig = properties.backends().get(defaultId);
        if (defaultConfig == null) {
            log.warn("prompt judge default backend {} has no configuration entry; "
                    + "every judge rule will fall to on_error until it is corrected", defaultId);
        } else if (!defaultConfig.enabled()) {
            log.warn("prompt judge default backend {} is disabled; "
                    + "every judge rule will fall to on_error", defaultId);
        } else if (!byId.containsKey(defaultId)) {
            log.warn("prompt judge default backend {} has no implementation registered; "
                    + "every judge rule will fall to on_error", defaultId);
        }
    }

    public Optional<PromptJudgeBackend> findEnabled(String backendId) {
        if (backendId == null || backendId.isBlank() || !isEnabled(backendId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(backends.get(backendId));
    }

    public PromptJudgeBackend require(String backendId) {
        return findEnabled(backendId)
                .orElseThrow(() -> new IllegalStateException(
                        "prompt judge backend is not enabled or unavailable: " + backendId));
    }

    public PromptJudgeBackend defaultBackend() {
        return require(properties.defaultBackend());
    }

    public String defaultBackendId() {
        return properties.defaultBackend();
    }

    public boolean isEnabled(String backendId) {
        PromptJudgeProperties.Backend config = properties.backends().get(backendId);
        return config != null && config.enabled();
    }
}

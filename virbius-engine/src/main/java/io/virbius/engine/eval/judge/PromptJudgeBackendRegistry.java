package io.virbius.engine.eval.judge;

import io.virbius.engine.config.PromptJudgeProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PromptJudgeBackendRegistry {

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

    public List<BackendView> catalog() {
        List<BackendView> out = new ArrayList<>();
        properties.backends().forEach((id, config) -> out.add(new BackendView(
                id,
                config.type(),
                config.enabled(),
                backends.containsKey(id),
                config.license(),
                config.commercialUse(),
                config.experimental(),
                config.modelVersion())));
        out.sort(Comparator.comparing(BackendView::id));
        return List.copyOf(out);
    }

    public record BackendView(
            String id,
            String type,
            boolean enabled,
            boolean available,
            String license,
            boolean commercialUse,
            boolean experimental,
            String modelVersion) {}
}

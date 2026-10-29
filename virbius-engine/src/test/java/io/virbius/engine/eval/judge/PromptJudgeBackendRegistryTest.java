package io.virbius.engine.eval.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.config.PromptJudgeProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptJudgeBackendRegistryTest {

    @Test
    void findsEnabledDefaultBackend() {
        PromptJudgeBackendRegistry registry = registry(Map.of(
                "neohorse-4b", backend(true),
                "startlux-4b", backend(false)));

        assertEquals("neohorse-4b", registry.defaultBackendId());
        assertEquals("neohorse-4b", registry.defaultBackend().id());
        assertTrue(registry.findEnabled("neohorse-4b").isPresent());
        assertTrue(registry.findEnabled("startlux-4b").isEmpty());
        assertFalse(registry.isEnabled("startlux-4b"));
    }

    @Test
    void requiresEnabledBackend() {
        PromptJudgeBackendRegistry registry = registry(Map.of("neohorse-4b", backend(true)));

        assertThrows(IllegalStateException.class, () -> registry.require("startlux-4b"));
        assertThrows(IllegalStateException.class, () -> registry.require(""));

        PromptJudgeBackendRegistry missingDefault = new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")),
                new PromptJudgeProperties(
                        "startlux-4b", 0, 0,
                        Map.of("neohorse-4b", backend(true))));
        assertThrows(IllegalStateException.class, missingDefault::defaultBackend);
    }

    @Test
    void catalogShowsConfiguredButMissingAdapter() {
        PromptJudgeBackendRegistry registry = registry(Map.of("neohorse-4b", backend(true)));

        List<PromptJudgeBackendRegistry.BackendView> catalog = registry.catalog();

        assertEquals(1, catalog.size());
        assertEquals("neohorse-4b", catalog.get(0).id());
        assertTrue(catalog.get(0).enabled());
        assertTrue(catalog.get(0).available());
    }

    @Test
    void rejectsDuplicateBackendIds() {
        PromptJudgeBackend a = new FakeBackend("neohorse-4b");
        PromptJudgeBackend b = new FakeBackend("neohorse-4b");

        assertThrows(
                IllegalStateException.class,
                () -> new PromptJudgeBackendRegistry(
                        List.of(a, b),
                        props(Map.of("neohorse-4b", backend(true)))));
    }

    private static PromptJudgeBackendRegistry registry(
            Map<String, PromptJudgeProperties.Backend> config) {
        return new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")), props(config));
    }

    private static PromptJudgeProperties props(
            Map<String, PromptJudgeProperties.Backend> config) {
        return new PromptJudgeProperties(
                "neohorse-4b", 0, 0, config);
    }

    private static PromptJudgeProperties.Backend backend(boolean enabled) {
        return new PromptJudgeProperties.Backend(
                "test", "http://127.0.0.1", enabled, "Apache-2.0", true, false, "v1", 0);
    }

    private static final class FakeBackend implements PromptJudgeBackend {

        private final String id;

        private FakeBackend(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public JudgeCapabilities capabilities() {
            return new JudgeCapabilities(true, false, true, 4096, 16, "Apache-2.0", true);
        }

        @Override
        public JudgeResult judge(JudgeRequest request) {
            return new JudgeResult(Map.of(), 0, null);
        }

        @Override
        public JudgeHealth health() {
            return JudgeHealth.up();
        }
    }
}

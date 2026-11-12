package io.virbius.engine.eval.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.config.PromptJudgeProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
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
                        "startlux-4b", 0, 0, 0, 0,
                        Map.of("neohorse-4b", backend(true))));
        assertThrows(IllegalStateException.class, missingDefault::defaultBackend);
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

    @Test
    void constructsFineWithEnabledBackendMissingModel() {
        // Startup only logs a WARN in this case; construction must not fail.
        PromptJudgeProperties.Backend blankModel = new PromptJudgeProperties.Backend(
                "http://127.0.0.1", true, "", "", 0);

        PromptJudgeBackendRegistry registry = registry(Map.of("neohorse-4b", blankModel));

        assertTrue(registry.findEnabled("neohorse-4b").isPresent());
    }

    @Test
    void warnsWhenDefaultBackendHasNoConfigEntry(CapturedOutput output) {
        new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")),
                new PromptJudgeProperties("neohorse", 0, 0, 0, 0,
                        Map.of("neohorse-4b", backend(true))));

        assertTrue(output.getAll().contains(
                "default backend neohorse has no configuration entry"));
    }

    @Test
    void warnsWhenEnabledBackendHasNoImplementation(CapturedOutput output) {
        new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")),
                new PromptJudgeProperties("neohorse-4b", 0, 0, 0, 0,
                        Map.of("neohorse-4b", backend(true), "startlux", backend(true))));

        assertTrue(output.getAll().contains(
                "backend startlux is enabled but no implementation is registered"));
    }

    @Test
    void warnsWhenDefaultBackendHasNoImplementation(CapturedOutput output) {
        new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("startlux-4b")),
                new PromptJudgeProperties("neohorse-4b", 0, 0, 0, 0,
                        Map.of("neohorse-4b", backend(true))));

        assertTrue(output.getAll().contains(
                "default backend neohorse-4b has no implementation registered"));
    }

    @Test
    void warnsWhenBackendHasNoConfigEntry(CapturedOutput output) {
        new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")),
                new PromptJudgeProperties("startlux-4b", 0, 0, 0, 0,
                        Map.of("startlux-4b", backend(false))));

        assertTrue(output.getAll().contains(
                "backend neohorse-4b is registered but has no configuration entry"));
    }

    @Test
    void warnsWhenDefaultBackendIsDisabled(CapturedOutput output) {
        new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")),
                new PromptJudgeProperties("neohorse-4b", 0, 0, 0, 0,
                        Map.of("neohorse-4b", backend(false))));

        assertTrue(output.getAll().contains(
                "default backend neohorse-4b is disabled"));
    }

    private static PromptJudgeBackendRegistry registry(
            Map<String, PromptJudgeProperties.Backend> config) {
        return new PromptJudgeBackendRegistry(
                List.of(new FakeBackend("neohorse-4b")), props(config));
    }

    private static PromptJudgeProperties props(
            Map<String, PromptJudgeProperties.Backend> config) {
        return new PromptJudgeProperties(
                "neohorse-4b", 0, 0, 0, 0, config);
    }

    private static PromptJudgeProperties.Backend backend(boolean enabled) {
        return new PromptJudgeProperties.Backend(
                "http://127.0.0.1", enabled, "NeoHorse-Jev-4B", "", 0);
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
            return new JudgeCapabilities(16, true, 2048);
        }

        @Override
        public JudgeResult judge(JudgeRequest request) {
            return new JudgeResult(Map.of(), 0, null);
        }
    }
}

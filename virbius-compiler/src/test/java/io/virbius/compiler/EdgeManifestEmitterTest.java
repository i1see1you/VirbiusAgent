package io.virbius.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EdgeManifestEmitterTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsPerAppManifestsFilteredByBindScope() throws Exception {
        Path bundle =
                Path.of("examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        if (!Files.exists(bundle)) {
            bundle = Path.of("../examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        }
        JsonNode root = yaml.readTree(Files.readString(bundle));

        Map<String, Object> beta = EdgeManifestEmitter.buildManifest(root, "beta");
        assertEquals("beta", beta.get("app_id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> betaRules = (List<Map<String, Object>>) beta.get("rules");
        assertEquals(1, betaRules.size());
        assertEquals("edge_l0_content_deny", betaRules.get(0).get("rule_id"));

        Map<String, Object> medical = EdgeManifestEmitter.buildManifest(root, "medical-prod");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> medRules = (List<Map<String, Object>>) medical.get("rules");
        assertEquals(2, medRules.size());
        assertTrue(medRules.stream().anyMatch(r -> "edge_medical_extra_deny".equals(r.get("rule_id"))));

        Map<String, Path> written = EdgeManifestEmitter.writeAll(tempDir, root, json);
        assertEquals(2, written.size());
        assertTrue(Files.exists(tempDir.resolve("default/beta/edge-manifest.json")));
        assertTrue(Files.exists(tempDir.resolve("default/medical-prod/edge-manifest.json")));
    }

    @Test
    void dlpRulesEmittedSeparately() throws Exception {
        String yamlText =
                """
                tenant_id: solo
                rules:
                  - rule_id: edge_kw
                    layer: edge
                    runtime: lua-dsl
                    rollout_state: dry_run
                    reason_code: KW
                    body: { list_type: deny, keywords: [bad] }
                  - rule_id: edge_dlp
                    layer: edge
                    runtime: dlp-dsl
                    rollout_state: full
                    reason_code: DLP
                    intent_action: allow
                    risk_score: 0
                    body: { entity_type: phone_cn }
                """;
        JsonNode root = yaml.readTree(yamlText);
        Map<String, Object> manifest = EdgeManifestEmitter.buildManifest(root, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) manifest.get("rules");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dlpRules = (List<Map<String, Object>>) manifest.get("dlp_rules");
        assertEquals(1, rules.size());
        assertEquals("edge_kw", rules.get(0).get("rule_id"));
        assertEquals(1, dlpRules.size());
        assertEquals("edge_dlp", dlpRules.get(0).get("rule_id"));
        assertEquals("allow", dlpRules.get(0).get("intent_action"));
        assertEquals(0, dlpRules.get(0).get("risk_score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sdk = (Map<String, Object>) manifest.get("sdk_config");
        assertEquals(1_800_000L, ((Number) sdk.get("dlp_vault_ttl_ms")).longValue());
    }
}

package io.virbius.control.service;

import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.gateway.GatewayListRedisService;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.EdgeArtifactMeta;
import io.virbius.policy.ListStorageKind;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.script.ScriptRuleBodies;
import io.virbius.control.policy.BindScopeExport;
import io.virbius.control.gateway.DlpRuleValidator;
import io.virbius.policy.EdgeManifestFilter;
import io.virbius.policy.SceneRegistry;
import io.virbius.control.policy.RuleBodyRefs;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.EdgeArtifactMetaRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private static final String DEFAULT_BUNDLE_ID = "poc-default";
    private static final String DEFAULT_BUNDLE_VERSION = "0.1.0";

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.nio.file.Path dataDir;
    private final RegistryRepository registryRepo;
    private final ListMetaRepository listMetaRepo;
    private final CumulativeRepository cumulativeRepo;
    private final TenantRolloutPolicyRepository policyRepository;
    private final String auditIngestUrl;
    private final String auditIngestToken;
    private final GatewayListRedisService gatewayListRedisService;
    private final EdgeArtifactMetaRepository edgeArtifactMetaRepository;
    private final boolean gatewayArtifactEnabled;
    private final boolean gatewayArtifactLocalFallback;

    public ArtifactService(
            @Value("${virbius.data-dir:./data}") String dataDir,
            RegistryRepository registryRepo,
            ListMetaRepository listMetaRepo,
            CumulativeRepository cumulativeRepo,
            TenantRolloutPolicyRepository policyRepository,
            @Value("${audit.edge.ingest-url:}") String auditIngestUrl,
            @Value("${audit.ingest.http.token:}") String auditIngestToken,
            GatewayListRedisService gatewayListRedisService,
            EdgeArtifactMetaRepository edgeArtifactMetaRepository,
            @Value("${virbius.gateway.artifact.enabled:true}") boolean gatewayArtifactEnabled,
            @Value("${virbius.gateway.artifact.local-fallback:false}") boolean gatewayArtifactLocalFallback) {
        this.dataDir = java.nio.file.Path.of(dataDir);
        this.registryRepo = registryRepo;
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
        this.policyRepository = policyRepository;
        this.auditIngestUrl = auditIngestUrl != null ? auditIngestUrl : "";
        this.auditIngestToken = auditIngestToken != null ? auditIngestToken : "";
        this.gatewayListRedisService = gatewayListRedisService;
        this.edgeArtifactMetaRepository = edgeArtifactMetaRepository;
        this.gatewayArtifactEnabled = gatewayArtifactEnabled;
        this.gatewayArtifactLocalFallback = gatewayArtifactLocalFallback;
    }

    public Map<String, String> write(String tenantId, Map<String, Object> bundleMetadata) {
        Map<String, String> paths = new LinkedHashMap<>();
        try {
            if (shouldWriteGatewayLocal()) {
                paths.put("gateway", writeGateway(tenantId, bundleMetadata).toString());
                paths.put("scene_registry", writeSceneRegistry(tenantId, bundleMetadata).toString());
            }
            paths.putAll(writeEdge(tenantId, bundleMetadata));
        } catch (Exception e) {
            log.warn("failed to write list artifacts: {}", e.getMessage());
            paths.put("error", e.getMessage());
        }
        return paths;
    }

    public Map<String, String> writeEdgeOnly(String tenantId) {
        Map<String, String> paths = new LinkedHashMap<>();
        try {
            Map<String, Object> metadata = registryRepo
                    .getBundle(tenantId, "poc-default", "0.1.0")
                    .map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                    .orElse(Map.of());
            paths.putAll(writeEdge(tenantId, metadata));
        } catch (Exception e) {
            log.warn("failed to write edge manifests: {}", e.getMessage());
            paths.put("error", e.getMessage());
        }
        return paths;
    }

    public void writeGatewayLocalFiles(String tenantId, Map<String, Object> bundleMetadata) {
        try {
            writeGateway(tenantId, bundleMetadata);
            writeSceneRegistry(tenantId, bundleMetadata);
        } catch (Exception e) {
            throw new IllegalStateException("failed to write gateway local files: " + e.getMessage(), e);
        }
    }

    public byte[] buildAccessListsJsonBytes(String tenantId, Map<String, Object> bundleMetadata) {
        try {
            Map<String, Object> root = buildGatewaySnapshot(tenantId, bundleMetadata);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build access-lists json: " + e.getMessage(), e);
        }
    }

    public byte[] buildSceneRegistryJsonBytes(String tenantId, Map<String, Object> bundleMetadata) {
        try {
            Map<String, Object> block = io.virbius.control.gateway.SceneRegistryHelper.registryBlock(bundleMetadata);
            if (block.isEmpty()) {
                block = Map.of("version", 1, "scenes", Map.of());
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("tenant_id", tenantId);
            root.put("scene_registry", block);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build scene-registry json: " + e.getMessage(), e);
        }
    }

    private boolean shouldWriteGatewayLocal() {
        return !gatewayArtifactEnabled || gatewayArtifactLocalFallback;
    }

    private java.nio.file.Path writeSceneRegistry(String tenantId, Map<String, Object> bundleMetadata) throws Exception {
        java.nio.file.Path dir = dataDir.resolve("gateway");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(tenantId + "-scene-registry.json");
        Map<String, Object> block = io.virbius.control.gateway.SceneRegistryHelper.registryBlock(bundleMetadata);
        if (block.isEmpty()) {
            block = Map.of("version", 1, "scenes", Map.of());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("scene_registry", block);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    private java.nio.file.Path writeGateway(String tenantId, Map<String, Object> bundleMetadata) throws Exception {
        java.nio.file.Path dir = dataDir.resolve("gateway");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(tenantId + "-access-lists.json");
        Map<String, Object> root = buildGatewaySnapshot(tenantId, bundleMetadata);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    /**
     * Writes the edge manifest for the given pool. The STABLE pool uses the backward-compatible
     * filename ({@code edge-manifest.json}); the CANARY pool uses a suffixed filename
     * ({@code edge-manifest-canary.json}) that exists only during an active deploy-rollout.
     */
    public Map<String, String> writeEdgeForPool(String tenantId, String pool) {
        Map<String, Object> metadata = registryRepo
                .getBundle(tenantId, "poc-default", "0.1.0")
                .map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                .orElse(Map.of());
        return writeEdgeWithPool(tenantId, metadata, pool);
    }

    Map<String, String> writeEdgeWithPool(String tenantId, Map<String, Object> bundleMetadata, String pool) {
        try {
            SceneRegistry registry =
                    io.virbius.control.gateway.SceneRegistryHelper.parseRegistry(bundleMetadata);
            List<RuleRevision> allRules = listEdgeRulesInExecutionPlane(tenantId);
            List<Map<String, Object>> scopes = allRules.stream()
                    .map(r -> r.scope() != null ? r.scope() : Map.<String, Object>of())
                    .toList();
            List<String> appIds = EdgeManifestFilter.collectAppIds(registry, scopes);

            Map<String, String> paths = new LinkedHashMap<>();
            java.nio.file.Path edgeBase = dataDir.resolve("edge");
            java.nio.file.Files.createDirectories(edgeBase);

            if (appIds.isEmpty()) {
                log.warn(
                        "skip edge manifests for tenant={}: scene_registry has no app_id",
                        tenantId);
                return paths;
            }

            for (String appId : appIds) {
                java.nio.file.Path dir = edgeBase.resolve(tenantId).resolve(appId);
                java.nio.file.Files.createDirectories(dir);
                List<RuleRevision> filtered = filterEdgeRulesForApp(allRules, appId, registry);
                String filename = "canary".equals(pool)
                        ? "edge-manifest-canary.json"
                        : "edge-manifest.json";
                java.nio.file.Path manifestFile = dir.resolve(filename);
                writeEdgeManifestFile(tenantId, appId, pool, filtered, manifestFile);
                paths.put("edge:" + appId + ":" + pool, manifestFile.toString());
            }
            if (!appIds.isEmpty()) {
                paths.put("edge" + ("canary".equals(pool) ? ":canary" : ""),
                        paths.get("edge:" + appIds.get(0) + ":" + pool));
            }
            return paths;
        } catch (Exception e) {
            log.warn("failed to write edge manifests for pool={}: {}", pool, e.getMessage());
            Map<String, String> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            return err;
        }
    }

    /**
     * Snapshot the current stable edge manifest (renames edge-manifest.json to
     * edge-manifest-stable.json). Returns the paths that were snapshotted, or empty if none.
     */
    public Map<String, String> snapshotStableEdge(String tenantId) {
        Map<String, String> paths = new LinkedHashMap<>();
        java.nio.file.Path edgeBase = dataDir.resolve("edge").resolve(tenantId);
        if (!java.nio.file.Files.isDirectory(edgeBase)) {
            return paths;
        }
        try (var apps = java.nio.file.Files.list(edgeBase)) {
            apps.filter(java.nio.file.Files::isDirectory).forEach(appDir -> {
                java.nio.file.Path stableFile = appDir.resolve("edge-manifest.json");
                java.nio.file.Path stableTarget = appDir.resolve("edge-manifest-stable.json");
                if (java.nio.file.Files.isRegularFile(stableFile)
                        && !java.nio.file.Files.isRegularFile(stableTarget)) {
                    try {
                        java.nio.file.Files.copy(stableFile, stableTarget);
                        String appId = appDir.getFileName().toString();
                        paths.put("edge:" + appId + ":stable", stableTarget.toString());
                    } catch (Exception e) {
                        log.warn("failed to snapshot stable edge manifest: {}", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("failed to list edge dirs for snapshot: {}", e.getMessage());
        }
        return paths;
    }

    /**
     * Promote canary edge manifest to stable: remove stable files, rename canary files to the
     * default stable name.
     */
    public Map<String, String> promoteEdgeCanary(String tenantId) {
        Map<String, String> paths = new LinkedHashMap<>();
        java.nio.file.Path edgeBase = dataDir.resolve("edge").resolve(tenantId);
        if (!java.nio.file.Files.isDirectory(edgeBase)) {
            return paths;
        }
        try (var apps = java.nio.file.Files.list(edgeBase)) {
            apps.filter(java.nio.file.Files::isDirectory).forEach(appDir -> {
                String appId = appDir.getFileName().toString();
                java.nio.file.Path stableFile = appDir.resolve("edge-manifest.json");
                java.nio.file.Path stableTarget = appDir.resolve("edge-manifest-stable.json");
                java.nio.file.Path canaryFile = appDir.resolve("edge-manifest-canary.json");

                try {
                    java.nio.file.Files.deleteIfExists(stableFile);
                    java.nio.file.Files.deleteIfExists(stableTarget);
                    if (java.nio.file.Files.isRegularFile(canaryFile)) {
                        java.nio.file.Files.move(canaryFile, stableFile);
                    }
                    edgeArtifactMetaRepository.delete(tenantId, appId, "canary");
                    paths.put("edge:" + appId, stableFile.toString());
                } catch (Exception e) {
                    log.warn("failed to promote edge canary: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("failed to list edge dirs for promotion: {}", e.getMessage());
        }
        return paths;
    }

    /** Package-visible for unit tests. */
    Map<String, Object> buildGatewaySnapshot(String tenantId) {
        return buildGatewaySnapshot(tenantId, Map.of());
    }

    Map<String, Object> buildGatewaySnapshot(String tenantId, Map<String, Object> bundleMetadata) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 2);
        root.put("tenant_id", tenantId);
        List<Map<String, Object>> memoryLists = buildMemoryListDefBlocks(tenantId);
        List<Map<String, Object>> redisIndex = gatewayListRedisService.publishRedisLists(tenantId);
        root.put("memory_lists", memoryLists);
        root.put("redis_list_index", redisIndex);
        root.put("cumulatives", buildCumulativeDefBlocks(tenantId));
        root.put("script_rules", buildScriptRuleBlocks(tenantId));
        List<io.virbius.control.domain.ContextVarBinding> ctxBindings =
                registryRepo.listContextBindings(tenantId, DEFAULT_BUNDLE_ID, DEFAULT_BUNDLE_VERSION);
        if (!ctxBindings.isEmpty()) {
            root.put("context_bindings", ContextBindingsHelper.toMetadataBlock(ctxBindings));
        }
        List<io.virbius.control.domain.ExtendedVar> extVars =
                registryRepo.listExtendedVars(tenantId, DEFAULT_BUNDLE_ID, DEFAULT_BUNDLE_VERSION);
        if (!extVars.isEmpty()) {
            root.put("extended_vars", ExtendedVarsHelper.toMetadataBlock(extVars));
        }
        Map<String, Object> sceneReg = io.virbius.control.gateway.SceneRegistryHelper.registryBlock(bundleMetadata);
        if (!sceneReg.isEmpty()) {
            root.put("scene_registry", sceneReg);
        }
        return root;
    }

    private List<Map<String, Object>> buildMemoryListDefBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            if (ListStorageKind.fromDimension(meta.dimension()) != ListStorageKind.MEMORY) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("list_name", meta.listName());
            block.put("dimension", meta.dimension());
            block.put("storage", "memory");
            if (meta.remark() != null && !meta.remark().isBlank()) {
                block.put("remark", meta.remark());
            }
            block.put("entries", AccessListService.entryMaps(listMetaRepo.listEntries(tenantId, meta.listName())));
            blocks.add(block);
        }
        blocks.sort((a, b) -> String.valueOf(a.get("list_name")).compareTo(String.valueOf(b.get("list_name"))));
        return blocks;
    }

    private List<Map<String, Object>> buildCumulativeDefBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (CumulativeDef def : cumulativeRepo.list(tenantId, "active")) {
            List<RuleRevision> bindings = listScriptBindingRules(tenantId, def.cumulativeName());
            List<BindScopeExport.RuleBindingSource> sources = new ArrayList<>();
            List<Map<String, Object>> bindingRules = new ArrayList<>();
            for (RuleRevision rule : bindings) {
                RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
                sources.add(new BindScopeExport.RuleBindingSource(refs.valueSource(), rule.scope()));
                Map<String, Object> entry = BindScopeExport.bindEntry(rule.scope());
                if (!bindingRules.contains(entry)) {
                    bindingRules.add(entry);
                }
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("cumulative_name", def.cumulativeName());
            block.put("dimension", def.dimension());
            block.put("window_kind", def.windowKind());
            block.put("window_minutes", def.windowMinutes());
            block.put("window_hours", def.windowHours());
            block.put("timezone", def.timezone());
            block.put("priority", def.priority());
            if (def.ingestPredicate() != null && !def.ingestPredicate().isBlank()) {
                block.put("ingest_predicate_runtime", def.ingestPredicateRuntime() != null && !def.ingestPredicateRuntime().isBlank()
                        ? def.ingestPredicateRuntime()
                        : "lua");
                block.put("ingest_predicate", def.ingestPredicate());
            }
            block.put("ingest_targets", BindScopeExport.ingestTargetsFromRules(sources));
            if (!bindingRules.isEmpty()) {
                block.put("binding_rules", bindingRules);
            } else {
                block.put("binding_rules", List.of(Map.of("bind_scope", "global")));
            }
            blocks.add(block);
        }
        blocks.sort((a, b) -> Integer.compare((int) b.get("priority"), (int) a.get("priority")));
        return blocks;
    }

    private List<Map<String, Object>> buildScriptRuleBlocks(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, "gateway")) {
            if (!RolloutStateHelper.inExecutionPlane(rule) || !"lua".equals(rule.runtime())) {
                continue;
            }
            blocks.add(toScriptRuleBlock(rule));
        }
        blocks.sort((a, b) -> Integer.compare((int) b.get("risk_score"), (int) a.get("risk_score")));
        return blocks;
    }

    private Map<String, Object> toScriptRuleBlock(RuleRevision rule) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("rule_id", rule.ruleId());
        block.put("rule_revision", rule.ruleRevision());
        block.put("reason_code", rule.reasonCode());
        block.put("risk_score", rule.riskScore());
        block.put("intent_action", rule.intentAction() != null ? rule.intentAction() : "deny");
        block.put("enforce_mode", rule.enforceMode());
        if (rule.canaryPercent() != null) {
            block.put("canary_percent", rule.canaryPercent());
        } else if (rule.exportedCanaryPercent() != null) {
            block.put("canary_percent", rule.exportedCanaryPercent());
        }
        String scriptBody = ScriptRuleBodies.asArtifactScript(rule.body(), rule.runtime());
        if (!scriptBody.isBlank()) {
            block.put("body", scriptBody);
        }
        BindScopeExport.putBindFields(block, rule.scope());
        return block;
    }

    private List<RuleRevision> listScriptBindingRules(String tenantId, String cumulativeName) {
        List<RuleRevision> out = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, null)) {
            if (!RolloutStateHelper.inExecutionPlane(rule)) {
                continue;
            }
            if (!"lua".equals(rule.runtime()) && !"groovy".equals(rule.runtime())) {
                continue;
            }
            String body = ScriptRuleBodies.asArtifactScript(rule.body(), rule.runtime());
            if (body.isBlank() || !referencesCumulative(body, cumulativeName)) {
                continue;
            }
            out.add(rule);
        }
        return out;
    }

    private static boolean referencesCumulative(String body, String cumulativeName) {
        if (body == null || cumulativeName == null || cumulativeName.isBlank()) {
            return false;
        }
        return body.contains(cumulativeName)
                && (body.contains("getCumulative") || body.contains("getCumulative("));
    }

    private Map<String, String> writeEdge(String tenantId, Map<String, Object> bundleMetadata) throws Exception {
        return writeEdgeWithPool(tenantId, bundleMetadata, "stable");
    }

    private List<RuleRevision> listEdgeRulesInExecutionPlane(String tenantId) {
        List<RuleRevision> out = new ArrayList<>();
        for (RuleRevision rule : registryRepo.listCurrentRules(tenantId, "edge")) {
            if (RolloutStateHelper.inExecutionPlane(rule)) {
                out.add(rule);
            }
        }
        return out;
    }

    private List<RuleRevision> filterEdgeRulesForApp(
            List<RuleRevision> rules, String appId, SceneRegistry registry) {
        List<RuleRevision> out = new ArrayList<>();
        for (RuleRevision rule : rules) {
            Map<String, Object> scope = rule.scope() != null ? rule.scope() : Map.of();
            if (EdgeManifestFilter.includesForApp(scope, appId, registry)) {
                out.add(rule);
            }
        }
        return out;
    }

    private void writeEdgeManifestFile(
            String tenantId, String appId, String pool, List<RuleRevision> rules, java.nio.file.Path manifestFile)
            throws Exception {
        TenantRolloutPolicy policy = policyRepository.getOrDefault(tenantId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenant_id", tenantId);
        root.put("manifest_version", "1");
        if (appId != null) {
            root.put("app_id", appId);
        }
        root.put("rules", buildEdgeRuleBlocks(rules));
        root.put("dlp_rules", buildDlpRuleBlocks(rules));

        Map<String, Object> sdk = new LinkedHashMap<>();
        if (!auditIngestUrl.isBlank()) {
            sdk.put("audit_ingest_url", auditIngestUrl);
        }
        if (!auditIngestToken.isBlank()) {
            sdk.put("audit_ingest_token", auditIngestToken);
        }
        sdk.put("audit_sample_rate_allow", policy.edgeAuditSampleRateAllow());
        sdk.put("audit_sample_rate_hit", 1.0);
        sdk.put("audit_flush_interval_ms", 30000);
        sdk.put("audit_queue_max", 500);
        sdk.put("canary_session_key", "device_id");
        sdk.put("dlp_vault_ttl_ms", 1_800_000L);
        root.put("sdk_config", sdk);

        Instant publishedAt = Instant.now();
        long revision = nextArtifactRevision(tenantId, appId, pool);
        root.put("artifact_revision", revision);
        root.put("published_at", publishedAt.toString());

        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), root);
        byte[] bytes = java.nio.file.Files.readAllBytes(manifestFile);
        String sha256 = sha256Hex(bytes);
        edgeArtifactMetaRepository.save(
                new EdgeArtifactMeta(tenantId, appId, pool, revision, sha256, publishedAt));
    }

    private long nextArtifactRevision(String tenantId, String appId, String pool) {
        return edgeArtifactMetaRepository
                        .get(tenantId, appId, pool)
                        .map(meta -> meta.artifactRevision() + 1)
                        .orElse(1L);
    }

    static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }

    /** Package-visible for unit tests. */
    List<Map<String, Object>> buildEdgeRuleBlocksForApp(
            String tenantId, String appId, Map<String, Object> bundleMetadata) {
        SceneRegistry registry =
                io.virbius.control.gateway.SceneRegistryHelper.parseRegistry(bundleMetadata);
        List<RuleRevision> filtered =
                filterEdgeRulesForApp(listEdgeRulesInExecutionPlane(tenantId), appId, registry);
        return buildEdgeRuleBlocks(filtered);
    }

    /** Package-visible for unit tests. */
    List<Map<String, Object>> buildDlpRuleBlocksForApp(
            String tenantId, String appId, Map<String, Object> bundleMetadata) {
        SceneRegistry registry =
                io.virbius.control.gateway.SceneRegistryHelper.parseRegistry(bundleMetadata);
        List<RuleRevision> filtered =
                filterEdgeRulesForApp(listEdgeRulesInExecutionPlane(tenantId), appId, registry);
        return buildDlpRuleBlocks(filtered);
    }

    private List<Map<String, Object>> buildEdgeRuleBlocks(List<RuleRevision> rules) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (RuleRevision rule : rules) {
            if (DlpRuleValidator.isDlpRuntime(rule.runtime())) {
                continue;
            }
            blocks.add(toRuleBlock(rule));
        }
        blocks.sort((a, b) -> String.valueOf(a.get("rule_id")).compareTo(String.valueOf(b.get("rule_id"))));
        return blocks;
    }

    private List<Map<String, Object>> buildDlpRuleBlocks(List<RuleRevision> rules) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (RuleRevision rule : rules) {
            if (!DlpRuleValidator.isDlpRuntime(rule.runtime())) {
                continue;
            }
            blocks.add(toRuleBlock(rule));
        }
        blocks.sort((a, b) -> String.valueOf(a.get("rule_id")).compareTo(String.valueOf(b.get("rule_id"))));
        return blocks;
    }

    private Map<String, Object> toRuleBlock(RuleRevision rule) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("rule_id", rule.ruleId());
        block.put("rule_revision", rule.ruleRevision());
        block.put("reason_code", rule.reasonCode());
        block.put("risk_score", rule.riskScore());
        block.put(
                "intent_action",
                DlpRuleValidator.isDlpRuntime(rule.runtime())
                        ? "allow"
                        : (rule.intentAction() != null ? rule.intentAction() : "deny"));
        block.put("enforce_mode", rule.enforceMode());
        block.put("rollout_state", rule.rolloutState() != null ? rule.rolloutState() : "dry_run");
        if (rule.exportedCanaryPercent() != null) {
            block.put("canary_percent", rule.exportedCanaryPercent());
        } else if (rule.canaryPercent() != null) {
            block.put("canary_percent", rule.canaryPercent());
        }
        if (rule.body() != null) {
            block.put("body", rule.body());
        }
        return block;
    }
}

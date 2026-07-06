package io.virbius.control.service;

import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.ContextVarBinding;
import io.virbius.control.domain.ExtendedVar;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.dto.request.ExtendedVarsRequest;
import io.virbius.control.domain.dto.request.GatewayRouteInput;
import io.virbius.control.domain.dto.request.GatewayRoutesRequest;
import io.virbius.control.domain.dto.request.SceneRegistryRequest;
import io.virbius.control.gateway.GatewayRoutesHelper;
import io.virbius.control.gateway.GatewayUriCoverage;
import io.virbius.control.gateway.SceneRegistryHelper;
import io.virbius.policy.SceneRegistry;
import io.virbius.control.repository.RegistryRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BundleMetadataService {

    private final RegistryRepository store;
    private final AccessListService accessListService;

    public BundleMetadataService(RegistryRepository store, AccessListService accessListService) {
        this.store = store;
        this.accessListService = accessListService;
    }

    private static final Pattern CTX_VAR_REF =
            Pattern.compile("ctx\\.var\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    // ===================== 因子引用校验（请求因子 / 扩展因子共用）=====================

    /**
     * 删除前校验：被删因子若仍被规则引用则拒绝。
     *
     * @param deletedLogicalNames 被删除的因子 logical 名集合
     */
    private void checkFactorReferences(String tenantId, Set<String> deletedLogicalNames) {
        if (deletedLogicalNames == null || deletedLogicalNames.isEmpty()) {
            return;
        }
        List<RuleRevision> allRules = store.listCurrentRules(tenantId, null);
        Map<String, List<String>> refs = new LinkedHashMap<>();
        for (RuleRevision rule : allRules) {
            Set<String> ruleRefs = collectReferencedVarNames(rule);
            for (String name : deletedLogicalNames) {
                if (ruleRefs.contains(name)) {
                    refs.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(rule.ruleId());
                }
            }
        }
        if (!refs.isEmpty()) {
            String detail = refs.entrySet().stream()
                    .map(e -> "'" + e.getKey() + "' (" + String.join(", ", e.getValue()) + ")")
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(
                    "Cannot delete factor(s) still referenced by rules: " + detail);
        }
    }

    private Set<String> collectReferencedVarNames(RuleRevision rule) {
        Set<String> names = new HashSet<>();
        Object body = rule.body();
        String script = null;
        if (body instanceof String s) {
            script = s;
        } else if (body instanceof Map<?, ?> m) {
            Object expr = m.get("expr");
            if (expr instanceof String s) {
                script = s;
            }
        }
        if (script == null || script.isBlank()) {
            return names;
        }
        Matcher m = CTX_VAR_REF.matcher(script);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    // ===================== metadata 聚合查询 =====================

    public Map<String, Object> getMetadata(String tenantId, String bundleId, String version) {
        BundleVersion bundle = requireBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = bundle.metadata() != null ? bundle.metadata() : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("bundle_id", bundleId);
        out.put("version", version);
        out.put("status", bundle.status());
        out.put("metadata", metadata);
        List<ContextVarBinding> ctxBindings = store.listContextBindings(tenantId, bundleId, version);
        out.put("context_bindings", ContextBindingsHelper.toMetadataBlock(ctxBindings));
        out.put("context_vars", ctxBindings.stream().map(this::varToMap).toList());
        List<ExtendedVar> extVars = store.listExtendedVars(tenantId, bundleId, version);
        out.put("extended_vars", ExtendedVarsHelper.toMetadataBlock(extVars));
        out.put("extended_var_list", extVars.stream().map(this::extVarToMap).toList());
        out.put("gateway", GatewayRoutesHelper.gatewayBlock(metadata));
        out.put("gateway_routes", GatewayRoutesHelper.parseRoutes(metadata).stream().map(this::routeToMap).toList());
        out.put("scene_registry", SceneRegistryHelper.registryBlock(metadata));
        out.put("scene_entries", SceneRegistryHelper.sceneRows(metadata));
        return out;
    }

    // ===================== 场景注册表 =====================

    public Map<String, Object> updateSceneRegistry(
            String tenantId, String bundleId, String version, SceneRegistryRequest body, boolean syncArtifacts) {
        SceneRegistry registry = SceneRegistryHelper.fromRequest(body);
        SceneRegistryHelper.validate(registry);
        BundleVersion bundle = requireDraftBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = new LinkedHashMap<>(
                bundle.metadata() != null ? bundle.metadata() : Map.of());
        SceneRegistryHelper.putRegistryMetadata(metadata, registry);
        syncScopeFromRegistry(metadata, registry, tenantId);
        List<String> gatewayUris = GatewayRoutesHelper.parseRoutes(metadata).stream()
                .map(r -> r.uri().trim())
                .toList();
        GatewayUriCoverage.validateSceneRegistryUris(gatewayUris, registry);
        store.updateBundleMetadata(tenantId, bundleId, version, metadata, bundle.metadataVersion());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("scene_registry", registry.toMetadataBlock());
        out.put("scene_entries", SceneRegistryHelper.sceneRows(metadata));
        out.put("scope", metadata.get("scope"));
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    // ===================== 网关路由 =====================

    public Map<String, Object> updateGatewayRoutes(
            String tenantId, String bundleId, String version, GatewayRoutesRequest body, boolean syncArtifacts) {
        if (body == null || body.routes() == null) {
            throw new IllegalArgumentException("routes required");
        }
        GatewayRoutesHelper.validateRoutes(body.routes());
        BundleVersion bundle = requireDraftBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = new LinkedHashMap<>(
                bundle.metadata() != null ? bundle.metadata() : Map.of());
        Map<String, Object> gateway = GatewayRoutesHelper.toGatewayMetadata(
                GatewayRoutesHelper.gatewayBlock(metadata),
                body.evaluate(),
                body.failMode(),
                body.cloudScan(),
                body.routes());
        metadata.put("gateway", gateway);
        syncScopeFromRegistry(metadata, SceneRegistryHelper.parseRegistry(metadata), tenantId);
        List<String> gatewayUris = body.routes().stream().map(r -> r.uri().trim()).toList();
        GatewayUriCoverage.validateSceneRegistryUris(gatewayUris, SceneRegistryHelper.parseRegistry(metadata));
        store.updateBundleMetadata(tenantId, bundleId, version, metadata, bundle.metadataVersion());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("gateway", gateway);
        out.put("gateway_routes", body.routes().stream().map(this::routeToMap).toList());
        out.put("scope", metadata.get("scope"));
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    private Map<String, Object> routeToMap(GatewayRouteInput r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", r.uri());
        m.put("methods", r.methods() != null ? r.methods() : List.of("POST"));
        return m;
    }

    private void syncScopeFromRegistry(Map<String, Object> metadata, SceneRegistry registry, String tenantId) {
        Object scope = metadata.get("scope");
        Map<String, Object> scopeMap = scope instanceof Map<?, ?> m
                ? new LinkedHashMap<>(castStringObjectMap(m))
                : new LinkedHashMap<>();
        if (!registry.scenes().isEmpty()) {
            scopeMap.put("scenes", registry.sceneIds());
            scopeMap.put("apps", registry.appIds());
        }
        if (!scopeMap.containsKey("tenants")) {
            scopeMap.put("tenants", List.of(tenantId));
        }
        metadata.put("scope", scopeMap);
    }

    // ===================== 请求因子（context bindings）=====================

    public Map<String, Object> updateContextBindings(
            String tenantId, String bundleId, String version, List<ContextVarBinding> bindings, boolean syncArtifacts) {
        requireDraftBundle(tenantId, bundleId, version);

        Set<String> extNames = store.listExtendedVars(tenantId, bundleId, version).stream()
                .map(ExtendedVar::logical).collect(Collectors.toSet());
        for (ContextVarBinding b : bindings) {
            if (extNames.contains(b.logical())) {
                throw new IllegalArgumentException("context var name conflicts with extended var: " + b.logical());
            }
        }

        Set<String> oldNames = store.listContextBindings(tenantId, bundleId, version).stream()
                .map(ContextVarBinding::logical).collect(Collectors.toSet());
        Set<String> newNames = bindings.stream().map(ContextVarBinding::logical).collect(Collectors.toSet());
        Set<String> deleted = new HashSet<>(oldNames);
        deleted.removeAll(newNames);
        checkFactorReferences(tenantId, deleted);

        store.replaceContextBindings(tenantId, bundleId, version, bindings);

        List<ContextVarBinding> saved = store.listContextBindings(tenantId, bundleId, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("context_bindings", ContextBindingsHelper.toMetadataBlock(saved));
        out.put("context_vars", saved.stream().map(this::varToMap).toList());
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    // ===================== 扩展因子（extended vars）=====================

    public Map<String, Object> updateExtendedVars(
            String tenantId, String bundleId, String version, List<ExtendedVar> vars, boolean syncArtifacts) {
        requireDraftBundle(tenantId, bundleId, version);

        Set<String> ctxNames = store.listContextBindings(tenantId, bundleId, version).stream()
                .map(ContextVarBinding::logical).collect(Collectors.toSet());
        for (ExtendedVar v : vars) {
            if (ctxNames.contains(v.logical())) {
                throw new IllegalArgumentException("extended var name conflicts with context binding: " + v.logical());
            }
        }

        Set<String> oldNames = store.listExtendedVars(tenantId, bundleId, version).stream()
                .map(ExtendedVar::logical).collect(Collectors.toSet());
        Set<String> newNames = vars.stream().map(ExtendedVar::logical).collect(Collectors.toSet());
        Set<String> deleted = new HashSet<>(oldNames);
        deleted.removeAll(newNames);
        checkFactorReferences(tenantId, deleted);

        store.replaceExtendedVars(tenantId, bundleId, version, vars);

        List<ExtendedVar> saved = store.listExtendedVars(tenantId, bundleId, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("extended_vars", ExtendedVarsHelper.toMetadataBlock(saved));
        out.put("extended_var_list", saved.stream().map(this::extVarToMap).toList());
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    public Map<String, Object> deleteExtendedVar(
            String tenantId, String bundleId, String version, String logical, boolean syncArtifacts) {
        requireDraftBundle(tenantId, bundleId, version);
        checkFactorReferences(tenantId, Set.of(logical));
        store.deleteExtendedVar(tenantId, bundleId, version, logical);
        List<ExtendedVar> saved = store.listExtendedVars(tenantId, bundleId, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("logical", logical);
        out.put("extended_vars", ExtendedVarsHelper.toMetadataBlock(saved));
        out.put("extended_var_list", saved.stream().map(this::extVarToMap).toList());
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    public Map<String, Object> deleteContextBinding(
            String tenantId, String bundleId, String version, String logical, boolean syncArtifacts) {
        requireDraftBundle(tenantId, bundleId, version);
        checkFactorReferences(tenantId, Set.of(logical));
        store.deleteContextBinding(tenantId, bundleId, version, logical);
        List<ContextVarBinding> saved = store.listContextBindings(tenantId, bundleId, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("logical", logical);
        out.put("context_bindings", ContextBindingsHelper.toMetadataBlock(saved));
        out.put("context_vars", saved.stream().map(this::varToMap).toList());
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    public Map<String, Object> syncGatewayArtifacts(String tenantId) {
        return accessListService.syncRules(tenantId);
    }

    // ===================== 请求解析 =====================

    public static List<ContextVarBinding> parseRequest(io.virbius.control.domain.dto.request.ContextBindingsRequest body) {
        if (body == null || body.vars() == null) {
            return List.of();
        }
        List<ContextVarBinding> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : body.vars()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String logical = row.get("logical") != null ? row.get("logical").toString() : null;
            String from = row.get("from") != null ? row.get("from").toString() : null;
            String name = row.get("name") != null ? row.get("name").toString() : null;
            String field = row.get("field") != null ? row.get("field").toString() : null;
            if (from == null || from.isBlank()) {
                from = ContextVarBinding.FROM_QUERY;
            }
            out.add(new ContextVarBinding(logical, from, name, field, parseScope(row.get("scope"))));
        }
        return List.copyOf(out);
    }

    public static List<ExtendedVar> parseExtendedVarsRequest(ExtendedVarsRequest body) {
        if (body == null || body.vars() == null) {
            return List.of();
        }
        List<ExtendedVar> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : body.vars()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String logical = row.get("logical") != null ? row.get("logical").toString() : null;
            String expr = row.get("expr") != null ? row.get("expr").toString() : null;
            ExtendedVar.Scope scope = parseExtScope(row.get("scope"));
            out.add(new ExtendedVar(logical, expr, scope));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static ExtendedVar.Scope parseExtScope(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new ExtendedVar.Scope(ExtendedVar.SCOPE_GLOBAL, List.of(), List.of());
        }
        String bs = m.get("bind_scope") != null ? m.get("bind_scope").toString() : null;
        List<String> appIds = List.of();
        List<String> scenes = List.of();
        if (m.get("app_ids") instanceof List<?> appList) {
            appIds = appList.stream().map(Object::toString).toList();
        }
        if (m.get("scenes") instanceof List<?> sceneList) {
            scenes = sceneList.stream().map(Object::toString).toList();
        }
        return new ExtendedVar.Scope(bs, appIds, scenes);
    }

    @SuppressWarnings("unchecked")
    private static ContextVarBinding.Scope parseScope(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new ContextVarBinding.Scope(ContextVarBinding.SCOPE_GLOBAL, List.of(), List.of());
        }
        String bs = m.get("bind_scope") != null ? m.get("bind_scope").toString() : null;
        List<String> appIds = List.of();
        List<String> scenes = List.of();
        if (m.get("app_ids") instanceof List<?> appList) {
            appIds = appList.stream().map(Object::toString).toList();
        }
        if (m.get("scenes") instanceof List<?> sceneList) {
            scenes = sceneList.stream().map(Object::toString).toList();
        }
        return new ContextVarBinding.Scope(bs, appIds, scenes);
    }

    // ===================== 辅助 =====================

    private BundleVersion requireBundle(String tenantId, String bundleId, String version) {
        return store.getBundle(tenantId, bundleId, version)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found"));
    }

    private BundleVersion requireDraftBundle(String tenantId, String bundleId, String version) {
        BundleVersion bundle = requireBundle(tenantId, bundleId, version);
        if (!"draft".equalsIgnoreCase(bundle.status())) {
            throw new IllegalArgumentException(
                    "bundle is not editable in status '" + bundle.status()
                            + "'; derive a new version to modify factors or metadata");
        }
        return bundle;
    }

    private Map<String, Object> varToMap(ContextVarBinding b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logical", b.logical());
        m.put("from", b.from());
        if (b.name() != null && !b.name().isBlank()) {
            m.put("name", b.name());
        }
        if (b.field() != null && !b.field().isBlank()) {
            m.put("field", b.field());
        }
        if (b.scope() != null && !ContextVarBinding.SCOPE_GLOBAL.equals(b.scope().bindScope())) {
            Map<String, Object> scopeMap = new LinkedHashMap<>();
            scopeMap.put("bind_scope", b.scope().bindScope());
            if (!b.scope().appIds().isEmpty()) {
                scopeMap.put("app_ids", b.scope().appIds());
            }
            if (!b.scope().scenes().isEmpty()) {
                scopeMap.put("scenes", b.scope().scenes());
            }
            m.put("scope", scopeMap);
        }
        return m;
    }

    private Map<String, Object> extVarToMap(ExtendedVar v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logical", v.logical());
        m.put("expr", v.expr());
        if (v.scope() != null && !ExtendedVar.SCOPE_GLOBAL.equals(v.scope().bindScope())) {
            Map<String, Object> scopeMap = new LinkedHashMap<>();
            scopeMap.put("bind_scope", v.scope().bindScope());
            if (!v.scope().appIds().isEmpty()) {
                scopeMap.put("app_ids", v.scope().appIds());
            }
            if (!v.scope().scenes().isEmpty()) {
                scopeMap.put("scenes", v.scope().scenes());
            }
            m.put("scope", scopeMap);
        }
        return m;
    }

    private static Map<String, Object> castStringObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }
}

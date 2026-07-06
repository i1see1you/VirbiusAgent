package io.virbius.control.repository;

import io.virbius.control.domain.BundleMetadataResources;
import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.ContextVarBinding;
import io.virbius.control.domain.ExtendedVar;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.enums.RolloutState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Primary
@Repository
public class JdbcRegistryRepository implements RegistryRepository {

    private final JdbcTemplate jdbc;

    public JdbcRegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BundleVersion> listBundles(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, bundle_id, version, status, publish_id, sync_ack_json, metadata_json, metadata_version
                FROM tb_bundles WHERE tenant_id = ?
                ORDER BY bundle_id, version
                """,
                bundleMapper(),
                tenantId);
    }

    @Override
    public Optional<BundleVersion> getBundle(String tenantId, String bundleId, String version) {
        List<BundleVersion> rows = jdbc.query(
                """
                SELECT tenant_id, bundle_id, version, status, publish_id, sync_ack_json, metadata_json, metadata_version
                FROM tb_bundles WHERE tenant_id = ? AND bundle_id = ? AND version = ?
                """,
                bundleMapper(),
                tenantId,
                bundleId,
                version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public BundleVersion createBundle(String tenantId, String bundleId) {
        Optional<BundleVersion> existing = getBundle(tenantId, bundleId, "0.1.0");
        if (existing.isPresent()) {
            return existing.get();
        }
        String metadataJson =
                "poc-default".equals(bundleId) ? JsonHelper.toJson(BundleMetadataResources.pocDefault010()) : "{}";
        try {
            jdbc.update(
                    """
                    INSERT INTO tb_bundles (tenant_id, bundle_id, version, status, metadata_json)
                    VALUES (?, ?, '0.1.0', 'draft', ?)
                    """,
                    tenantId,
                    bundleId,
                    metadataJson);
        } catch (DuplicateKeyException ignored) {
        }
        return getBundle(tenantId, bundleId, "0.1.0").orElseThrow();
    }

    @Override
    @Transactional
    public RuleRevision upsertRule(String tenantId, RuleRevision draft) {
        String ruleId = draft.ruleId();
        int next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(rule_revision), 0) + 1 FROM tb_rule_history WHERE tenant_id = ? AND rule_id = ?",
                Integer.class,
                tenantId,
                ruleId);
        String nowStr = TimeHelper.nowIso();

        jdbc.update(
                "UPDATE tb_rule_history SET effective_to = ? WHERE tenant_id = ? AND rule_id = ? AND effective_to IS NULL",
                nowStr,
                tenantId,
                ruleId);

        String bodyJson = JsonHelper.toJson(draft.body());
        String scopeJson = JsonHelper.toJson(draft.scope());
        String rollout = draft.rolloutState() != null ? draft.rolloutState() : RolloutState.DRAFT.value();

        jdbc.update(
                """
                INSERT INTO tb_rule_history (
                  tenant_id, rule_id, rule_revision, bundle_id, layer, runtime,
                  reason_code, risk_score, intent_action, is_async, async_action_config,
                  scope_json, body_json,
                  rollout_state, canary_percent, effective_from, modified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                ruleId,
                next,
                draft.bundleId() != null ? draft.bundleId() : "poc-default",
                draft.layer(),
                draft.runtime(),
                draft.reasonCode(),
                RiskScore.normalize(draft.riskScore()),
                draft.intentAction() != null
                        ? draft.intentAction()
                        : io.virbius.policy.IntentAction.defaultForRisk(draft.riskScore()),
                draft.isAsync(),
                draft.asyncActionConfig(),
                scopeJson,
                bodyJson,
                rollout,
                draft.canaryPercent(),
                nowStr,
                nowStr);

        upsertRulesCurrent(tenantId, ruleId, next, draft, nowStr, rollout);

        return getRuleRevision(tenantId, ruleId, next).orElseThrow();
    }

    private void upsertRulesCurrent(
            String tenantId, String ruleId, int revision, RuleRevision draft, String nowStr, String rollout) {
        int updated = jdbc.update(
                """
                UPDATE tb_rules_current SET
                  current_revision = ?, bundle_id = ?, layer = ?, runtime = ?,
                  reason_code = ?, intent_action = ?, rollout_state = ?, updated_at = ?
                WHERE tenant_id = ? AND rule_id = ?
                """,
                revision,
                draft.bundleId() != null ? draft.bundleId() : "poc-default",
                draft.layer(),
                draft.runtime(),
                draft.reasonCode(),
                draft.intentAction() != null
                        ? draft.intentAction()
                        : io.virbius.policy.IntentAction.defaultForRisk(draft.riskScore()),
                rollout,
                nowStr,
                tenantId,
                ruleId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO tb_rules_current (
                      tenant_id, rule_id, current_revision, bundle_id, layer, runtime,
                      reason_code, intent_action, rollout_state, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId,
                    ruleId,
                    revision,
                    draft.bundleId() != null ? draft.bundleId() : "poc-default",
                    draft.layer(),
                    draft.runtime(),
                    draft.reasonCode(),
                    draft.intentAction() != null
                            ? draft.intentAction()
                            : io.virbius.policy.IntentAction.defaultForRisk(draft.riskScore()),
                    rollout,
                    nowStr);
        } catch (DuplicateKeyException e) {
            upsertRulesCurrent(tenantId, ruleId, revision, draft, nowStr, rollout);
        }
    }

    @Override
    public List<RuleRevision> listCurrentRules(String tenantId, String layer) {
        String sql;
        Object[] args;
        if (layer == null || layer.isBlank()) {
            sql = """
                  SELECT h.* FROM tb_rules_current c
                  JOIN tb_rule_history h
                    ON h.tenant_id = c.tenant_id AND h.rule_id = c.rule_id
                       AND h.rule_revision = c.current_revision
                  WHERE c.tenant_id = ?
                  """;
            args = new Object[] {tenantId};
        } else {
            sql = """
                  SELECT h.* FROM tb_rules_current c
                  JOIN tb_rule_history h
                    ON h.tenant_id = c.tenant_id AND h.rule_id = c.rule_id
                       AND h.rule_revision = c.current_revision
                  WHERE c.tenant_id = ? AND c.layer = ?
                  """;
            args = new Object[] {tenantId, layer};
        }
        return jdbc.query(sql, ruleMapper(), args).stream()
                .sorted(Comparator.comparing(RuleRevision::ruleId))
                .toList();
    }

    @Override
    public Optional<RuleRevision> getRuleRevision(String tenantId, String ruleId, int revision) {
        List<RuleRevision> rows = jdbc.query(
                "SELECT * FROM tb_rule_history WHERE tenant_id = ? AND rule_id = ? AND rule_revision = ?",
                ruleMapper(),
                tenantId,
                ruleId,
                revision);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public RuleRevision updateRollout(String tenantId, String ruleId, String rolloutState, Integer canaryPercent) {
        RuleRevision current = getCurrentRule(tenantId, ruleId).orElseThrow();
        RuleRevision draft = new RuleRevision(
                tenantId,
                ruleId,
                0,
                current.bundleId(),
                current.layer(),
                current.runtime(),
                current.reasonCode(),
                current.riskScore(),
                current.intentAction(),
                current.scope(),
                current.body(),
                rolloutState,
                canaryPercent,
                null,
                null,
                null,
                current.isAsync(),
                current.asyncActionConfig());
        return upsertRule(tenantId, draft);
    }

    @Override
    @Deprecated
    public RuleRevision updateRuntime(String tenantId, String ruleId, String enforceMode, Integer canaryPercent) {
        String rollout = switch (enforceMode != null ? enforceMode.toLowerCase() : "dry_run") {
            case "canary" -> RolloutState.CANARY.value();
            case "full" -> RolloutState.FULL.value();
            default -> RolloutState.DRY_RUN.value();
        };
        RuleRevision current = getCurrentRule(tenantId, ruleId).orElseThrow();
        if (RolloutState.DRY_RUN.value().equals(RolloutStateHelper.stateOf(current)) && RolloutState.FULL.value().equals(rollout)) {
            throw new IllegalArgumentException("dry_run -> full is permanently forbidden");
        }
        return updateRollout(tenantId, ruleId, rollout, canaryPercent);
    }

    @Override
    @Deprecated
    public RuleRevision updateRuleStatus(String tenantId, String ruleId, String ruleStatus) {
        RuleRevision current = getCurrentRule(tenantId, ruleId).orElseThrow();
        String rollout =
                switch (ruleStatus != null ? ruleStatus.toLowerCase() : "draft") {
                    case "active" -> RolloutState.DRY_RUN.value();
                    case "disabled" -> RolloutState.DISABLED.value();
                    default -> RolloutState.DRAFT.value();
                };
        if (rollout.equals(RolloutStateHelper.stateOf(current))) {
            return current;
        }
        return updateRollout(tenantId, ruleId, rollout, null);
    }

    @Override
    public List<RuleRevision> listRuleRevisions(String tenantId, String ruleId) {
        return jdbc.query(
                """
                SELECT * FROM tb_rule_history
                WHERE tenant_id = ? AND rule_id = ?
                ORDER BY rule_revision
                """,
                ruleMapper(),
                tenantId,
                ruleId);
    }

    @Override
    public Optional<RuleRevision> getCurrentRule(String tenantId, String ruleId) {
        List<Integer> revs = jdbc.query(
                "SELECT current_revision FROM tb_rules_current WHERE tenant_id = ? AND rule_id = ?",
                (rs, i) -> rs.getInt(1),
                tenantId,
                ruleId);
        if (revs.isEmpty()) {
            return Optional.empty();
        }
        return getRuleRevision(tenantId, ruleId, revs.get(0));
    }

    @Override
    public int countByRolloutStates(String tenantId, List<String> rolloutStates, String excludeRuleId) {
        String states = String.join("','", rolloutStates);
        String sql = "SELECT COUNT(*) FROM tb_rules_current"
                + " WHERE tenant_id = ? AND rollout_state IN ('" + states + "')"
                + " AND rule_id <> ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, tenantId, excludeRuleId);
        return count != null ? count : 0;
    }

    @Override
    public void updateBundleMetadata(
            String tenantId, String bundleId, String version, Map<String, Object> metadata, int expectedVersion) {
        String nowStr = TimeHelper.nowIso();
        int affected;
        if (expectedVersion < 0) {
            affected = jdbc.update(
                    """
                    UPDATE tb_bundles SET metadata_json = ?, updated_at = ?
                    WHERE tenant_id = ? AND bundle_id = ? AND version = ?
                    """,
                    JsonHelper.toJson(metadata != null ? metadata : Map.of()),
                    nowStr,
                    tenantId,
                    bundleId,
                    version);
        } else {
            affected = jdbc.update(
                    """
                    UPDATE tb_bundles SET metadata_json = ?, metadata_version = metadata_version + 1, updated_at = ?
                    WHERE tenant_id = ? AND bundle_id = ? AND version = ? AND metadata_version = ?
                    """,
                    JsonHelper.toJson(metadata != null ? metadata : Map.of()),
                    nowStr,
                    tenantId,
                    bundleId,
                    version,
                    expectedVersion);
        }
        if (affected == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "bundle metadata changed by another transaction: " + tenantId + "/" + bundleId + "/" + version);
        }
    }

    private RowMapper<BundleVersion> bundleMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return (rs, rowNum) -> {
            String syncJson = rs.getString("sync_ack_json");
            Map<String, Object> syncAck = Map.of();
            if (syncJson != null && !syncJson.isBlank()) {
                try {
                    syncAck = mapper.readValue(syncJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                } catch (Exception ignored) {
                    syncAck = Map.of();
                }
            }
            return new BundleVersion(
                    rs.getString("tenant_id"),
                    rs.getString("bundle_id"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getString("publish_id"),
                    syncAck,
                    JsonHelper.mapFromJson(rs.getString("metadata_json")),
                    rs.getInt("metadata_version"));
        };
    }

    private RowMapper<RuleRevision> ruleMapper() {
        return (rs, rowNum) -> mapRule(rs);
    }

    private RuleRevision mapRule(ResultSet rs) throws SQLException {
        int risk = rs.getInt("risk_score");
        String intent = rs.getString("intent_action");
        if (intent == null || intent.isBlank()) {
            intent = io.virbius.policy.IntentAction.defaultForRisk(risk);
        }
        return new RuleRevision(
                rs.getString("tenant_id"),
                rs.getString("rule_id"),
                rs.getInt("rule_revision"),
                rs.getString("bundle_id"),
                rs.getString("layer"),
                rs.getString("runtime"),
                rs.getString("reason_code"),
                risk,
                intent,
                JsonHelper.mapFromJson(rs.getString("scope_json")),
                JsonHelper.bodyFromJson(rs.getString("body_json")),
                rs.getString("rollout_state"),
                (Integer) rs.getObject("canary_percent"),
                TimeHelper.parseInstant(rs.getString("modified_at")),
                TimeHelper.parseInstant(rs.getString("effective_from")),
                TimeHelper.parseInstant(rs.getString("effective_to")),
                rs.getInt("is_async") != 0,
                rs.getString("async_action_config"));
    }

    // ===================== 请求因子（context bindings）=====================

    @Override
    public List<ContextVarBinding> listContextBindings(
            String tenantId, String bundleId, String version, boolean includeDeleted) {
        String sql = includeDeleted
                ? "SELECT * FROM tb_context_bindings WHERE tenant_id=? AND bundle_id=? AND version=? ORDER BY logical"
                : "SELECT * FROM tb_context_bindings WHERE tenant_id=? AND bundle_id=? AND version=? AND deleted_at IS NULL ORDER BY logical";
        return jdbc.query(sql, ctxBindingMapper(), tenantId, bundleId, version);
    }

    @Override
    @Transactional
    public void replaceContextBindings(
            String tenantId, String bundleId, String version, List<ContextVarBinding> bindings) {
        List<ContextVarBinding> current = listContextBindings(tenantId, bundleId, version, true);
        Map<String, ContextVarBinding> currentByName = new LinkedHashMap<>();
        for (ContextVarBinding b : current) {
            currentByName.put(b.logical(), b);
        }
        Set<String> incomingNames = bindings.stream().map(ContextVarBinding::logical).collect(Collectors.toSet());
        String nowStr = TimeHelper.nowIso();

        for (ContextVarBinding b : bindings) {
            ContextVarBinding existing = currentByName.get(b.logical());
            if (existing == null || existing.isDeleted()) {
                jdbc.update(
                        """
                        INSERT INTO tb_context_bindings
                          (tenant_id, bundle_id, version, logical, src_from, src_name, src_field, scope_json,
                           deleted_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                        """,
                        tenantId, bundleId, version, b.logical(), b.from(), b.name(), b.field(),
                        JsonHelper.toJson(ctxScopeToMap(b.scope())),
                        nowStr, nowStr);
            } else {
                jdbc.update(
                        """
                        UPDATE tb_context_bindings SET src_from=?, src_name=?, src_field=?, scope_json=?,
                          deleted_at=NULL, updated_at=?
                        WHERE tenant_id=? AND bundle_id=? AND version=? AND logical=?
                        """,
                        b.from(), b.name(), b.field(),
                        JsonHelper.toJson(ctxScopeToMap(b.scope())),
                        nowStr, tenantId, bundleId, version, b.logical());
            }
        }
        for (Map.Entry<String, ContextVarBinding> e : currentByName.entrySet()) {
            if (!incomingNames.contains(e.getKey()) && !e.getValue().isDeleted()) {
                jdbc.update(
                        "UPDATE tb_context_bindings SET deleted_at=?, updated_at=? WHERE tenant_id=? AND bundle_id=? AND version=? AND logical=?",
                        nowStr, nowStr, tenantId, bundleId, version, e.getKey());
            }
        }
    }

    private RowMapper<ContextVarBinding> ctxBindingMapper() {
        return (rs, rowNum) -> {
            String scopeJson = rs.getString("scope_json");
            Map<String, Object> scopeMap = JsonHelper.mapFromJson(scopeJson);
            ContextVarBinding.Scope scope = parseCtxScope(scopeMap);
            String deletedAt = rs.getString("deleted_at");
            String name = rs.getString("src_name");
            String field = rs.getString("src_field");
            String logical = rs.getString("logical");
            String from = rs.getString("src_from");
            return new ContextVarBinding(logical, from, name, field, scope, deletedAt);
        };
    }

    private static ContextVarBinding.Scope parseCtxScope(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return new ContextVarBinding.Scope(ContextVarBinding.SCOPE_GLOBAL, List.of(), List.of());
        }
        String bs = m.get("bind_scope") != null ? m.get("bind_scope").toString() : null;
        List<String> appIds = m.get("app_ids") instanceof List<?> al ? al.stream().map(Object::toString).toList() : List.of();
        List<String> scenes = m.get("scenes") instanceof List<?> sl ? sl.stream().map(Object::toString).toList() : List.of();
        return new ContextVarBinding.Scope(bs, appIds, scenes);
    }

    // ===================== 扩展因子（extended vars）=====================

    @Override
    public List<ExtendedVar> listExtendedVars(
            String tenantId, String bundleId, String version, boolean includeDeleted) {
        String sql = includeDeleted
                ? "SELECT * FROM tb_extended_vars WHERE tenant_id=? AND bundle_id=? AND version=? ORDER BY logical"
                : "SELECT * FROM tb_extended_vars WHERE tenant_id=? AND bundle_id=? AND version=? AND deleted_at IS NULL ORDER BY logical";
        return jdbc.query(sql, extVarMapper(), tenantId, bundleId, version);
    }

    @Override
    @Transactional
    public void replaceExtendedVars(
            String tenantId, String bundleId, String version, List<ExtendedVar> vars) {
        List<ExtendedVar> current = listExtendedVars(tenantId, bundleId, version, true);
        Map<String, ExtendedVar> currentByName = new LinkedHashMap<>();
        for (ExtendedVar v : current) {
            currentByName.put(v.logical(), v);
        }
        Set<String> incomingNames = vars.stream().map(ExtendedVar::logical).collect(Collectors.toSet());
        String nowStr = TimeHelper.nowIso();

        for (ExtendedVar v : vars) {
            ExtendedVar existing = currentByName.get(v.logical());
            if (existing == null || existing.isDeleted()) {
                jdbc.update(
                        """
                        INSERT INTO tb_extended_vars
                          (tenant_id, bundle_id, version, logical, expr, scope_json, deleted_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                        """,
                        tenantId, bundleId, version, v.logical(), v.expr(),
                        JsonHelper.toJson(extScopeToMap(v.scope())),
                        nowStr, nowStr);
            } else {
                jdbc.update(
                        """
                        UPDATE tb_extended_vars SET expr=?, scope_json=?, deleted_at=NULL, updated_at=?
                        WHERE tenant_id=? AND bundle_id=? AND version=? AND logical=?
                        """,
                        v.expr(), JsonHelper.toJson(extScopeToMap(v.scope())),
                        nowStr, tenantId, bundleId, version, v.logical());
            }
        }
        for (Map.Entry<String, ExtendedVar> e : currentByName.entrySet()) {
            if (!incomingNames.contains(e.getKey()) && !e.getValue().isDeleted()) {
                jdbc.update(
                        "UPDATE tb_extended_vars SET deleted_at=?, updated_at=? WHERE tenant_id=? AND bundle_id=? AND version=? AND logical=?",
                        nowStr, nowStr, tenantId, bundleId, version, e.getKey());
            }
        }
    }

    @Override
    public void deleteExtendedVar(String tenantId, String bundleId, String version, String logical) {
        String now = TimeHelper.nowIso();
        int n = jdbc.update(
                "UPDATE tb_extended_vars SET deleted_at=?, updated_at=? WHERE tenant_id=? AND bundle_id=? AND version=? AND logical=? AND deleted_at IS NULL",
                now, now, tenantId, bundleId, version, logical);
        if (n == 0) {
            throw new IllegalArgumentException("extended var not found or already deleted: " + logical);
        }
    }

    @Override
    public void deleteContextBinding(String tenantId, String bundleId, String version, String logical) {
        String now = TimeHelper.nowIso();
        int n = jdbc.update(
                "UPDATE tb_context_bindings SET deleted_at=?, updated_at=? WHERE tenant_id=? AND bundle_id=? AND version=? AND logical=? AND deleted_at IS NULL",
                now, now, tenantId, bundleId, version, logical);
        if (n == 0) {
            throw new IllegalArgumentException("context binding not found or already deleted: " + logical);
        }
    }

    private RowMapper<ExtendedVar> extVarMapper() {
        return (rs, rowNum) -> {
            String scopeJson = rs.getString("scope_json");
            Map<String, Object> scopeMap = JsonHelper.mapFromJson(scopeJson);
            ExtendedVar.Scope scope = parseExtScope(scopeMap);
            String deletedAt = rs.getString("deleted_at");
            return new ExtendedVar(rs.getString("logical"), rs.getString("expr"), scope, deletedAt);
        };
    }

    private static ExtendedVar.Scope parseExtScope(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return new ExtendedVar.Scope(ExtendedVar.SCOPE_GLOBAL, List.of(), List.of());
        }
        String bs = m.get("bind_scope") != null ? m.get("bind_scope").toString() : null;
        List<String> appIds = m.get("app_ids") instanceof List<?> al ? al.stream().map(Object::toString).toList() : List.of();
        List<String> scenes = m.get("scenes") instanceof List<?> sl ? sl.stream().map(Object::toString).toList() : List.of();
        return new ExtendedVar.Scope(bs, appIds, scenes);
    }

    private static Map<String, Object> ctxScopeToMap(ContextVarBinding.Scope s) {
        if (s == null) {
            return null;
        }
        return scopeToMap(s.bindScope(), s.appIds(), s.scenes());
    }

    private static Map<String, Object> extScopeToMap(ExtendedVar.Scope s) {
        if (s == null) {
            return null;
        }
        return scopeToMap(s.bindScope(), s.appIds(), s.scenes());
    }

    private static Map<String, Object> scopeToMap(String bindScope, List<String> appIds, List<String> scenes) {
        if (bindScope == null || bindScope.isBlank() || bindScope.equals(ContextVarBinding.SCOPE_GLOBAL)) {
            if ((appIds == null || appIds.isEmpty()) && (scenes == null || scenes.isEmpty())) {
                return null;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bind_scope", bindScope != null ? bindScope : ContextVarBinding.SCOPE_GLOBAL);
        if (appIds != null && !appIds.isEmpty()) {
            m.put("app_ids", appIds);
        }
        if (scenes != null && !scenes.isEmpty()) {
            m.put("scenes", scenes);
        }
        return m;
    }
}

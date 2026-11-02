package io.virbius.control.evaluation;

import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.evaluation.JdbcEvaluationSuppressionsRepository.NewRule;
import io.virbius.control.evaluation.JdbcEvaluationSuppressionsRepository.Row;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 评估抑制注册表服务：入口校验（reason 治理 / match_type 一致性）、默认有效期、
 * 租户规范化（与代理 X-VE-Tenant 同一口径：小写、缺省 default）。
 *
 * <p>写操作调用方需 TENANT_ADMIN（由 ApiKeyRoutePolicy 按 /admin/tenants/** 路径强制）；
 * created_by 取当前凭据主体（本地无鉴权形态下为 "local"）。
 */
@Service
public class EvaluationSuppressionsService {

    /** 默认有效期 90 天（到期即不再导出/注入，浮回告警；显式 expires_at 可覆盖，"never"=永久）。 */
    static final int DEFAULT_EXPIRY_DAYS = 90;

    /** reason 下限：杜绝 "TODO" 式形式化接受。 */
    private static final int REASON_MIN_CHARS = 10;

    private static final int MAX_PATHS = 64;
    private static final int MAX_PATH_LEN = 256;

    private final JdbcEvaluationSuppressionsRepository repo;

    public EvaluationSuppressionsService(JdbcEvaluationSuppressionsRepository repo) {
        this.repo = repo;
    }

    public List<Row> list(String tenantId, boolean includeInactive) {
        return repo.list(normalizeTenant(tenantId), includeInactive);
    }

    public Row find(String tenantId, String id) {
        return repo.find(normalizeTenant(tenantId), id)
                .orElseThrow(() -> new NoSuchElementException("suppression not found: " + id));
    }

    /** 活跃规则 yaml（供代理注入与 /export）；无活跃规则返回 null。 */
    public String activeYaml(String tenantId) {
        return repo.activeYaml(normalizeTenant(tenantId));
    }

    public Row create(String tenantId, CreateRequest req) {
        String tenant = normalizeTenant(tenantId);
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String reason = requireReason(req.reason());
        String matchType = normalizeMatchType(req.matchType());
        String riskId = trimmed(req.riskId());
        String checkId = trimmed(req.checkId());
        List<String> paths = normalizedPaths(req.paths());
        switch (matchType) {
            case JdbcEvaluationSuppressionsRepository.MATCH_RISK_ID -> {
                requireIdentifier(riskId, "risk_id");
                checkId = null;
                paths = null;
            }
            case JdbcEvaluationSuppressionsRepository.MATCH_CHECK_PATHS -> {
                requireIdentifier(checkId, "check_id");
                if (paths == null || paths.isEmpty()) {
                    throw new IllegalArgumentException("match_type=check_paths 须提供 paths（glob 列表）");
                }
                riskId = null;
            }
            case JdbcEvaluationSuppressionsRepository.MATCH_CHECK_MUTE -> {
                requireIdentifier(checkId, "check_id");
                riskId = null;
                paths = null;
            }
            default -> throw new IllegalArgumentException("match_type 须为 risk_id|check_paths|check_mute");
        }
        String expiresAt = resolveExpiry(req.expiresAt());
        String createdBy = currentPrincipal();
        try {
            return repo.insert(new NewRule(trimmed(req.id()), tenant, matchType, riskId, checkId,
                    paths, reason, trimmed(req.sevAtAccept()), createdBy, expiresAt));
        } catch (org.springframework.dao.DataAccessException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            throw new IllegalArgumentException(
                    "suppression id 已存在（同租户内不可重复接受同一 id）: " + trimmed(req.id()));
        }
    }

    /**
     * 主键冲突判定需跨方言：MySQL 走 DuplicateKeyException（SQLState 23000），
     * xerial SQLite 驱动不翻译、仅 UncategorizedSQLException 携带 SQLITE_CONSTRAINT 文案。
     */
    private static boolean isDuplicateKey(org.springframework.dao.DataAccessException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        String msg = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase();
        return msg.contains("duplicate") || (msg.contains("constraint") && msg.contains("unique"))
                || msg.contains("primary key constraint failed");
    }

    public Row withdraw(String tenantId, String id, long expectedVersion) {
        return repo.withdraw(normalizeTenant(tenantId), id, expectedVersion);
    }

    // ---------- 内部 ----------

    /** 与 EvaluationProxyController.forward 的 X-VE-Tenant 规范化口径一致。 */
    static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank()
                ? "default" : tenantId.strip().toLowerCase();
    }

    private static String requireReason(String reason) {
        String r = trimmed(reason);
        if (r == null) {
            throw new IllegalArgumentException("reason 必填（接受风险必须留痕）");
        }
        if (r.length() < REASON_MIN_CHARS) {
            throw new IllegalArgumentException(
                    "reason 至少 " + REASON_MIN_CHARS + " 字（当前 " + r.length() + "）");
        }
        if (r.toUpperCase().startsWith("TODO")) {
            throw new IllegalArgumentException(
                    "reason 不能以 TODO 开头：请填写实际接受理由（如\"测试夹具合成 AKID，非真实凭据\"）");
        }
        if (r.length() > 500) {
            throw new IllegalArgumentException("reason 过长（≤500 字）");
        }
        return r;
    }

    private static String normalizeMatchType(String matchType) {
        String m = trimmed(matchType);
        if (m == null) {
            throw new IllegalArgumentException("match_type 必填：risk_id|check_paths|check_mute");
        }
        return m;
    }

    private static void requireIdentifier(String v, String field) {
        if (v == null) {
            throw new IllegalArgumentException(matchFieldError(field));
        }
        if (v.length() > 64 || !v.matches("[A-Za-z0-9_@:./-]+")) {
            throw new IllegalArgumentException(field + " 含非法字符或超长（≤64，字母数字与 _@:./-）");
        }
    }

    private static String matchFieldError(String field) {
        return field + " 必填（与 match_type 一致）";
    }

    private static List<String> normalizedPaths(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.size() > MAX_PATHS) {
            throw new IllegalArgumentException("paths 数超上限（" + MAX_PATHS + "）");
        }
        return raw.stream().map(p -> {
            String s = p == null ? "" : p.trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("paths 含空项");
            }
            if (s.length() > MAX_PATH_LEN) {
                throw new IllegalArgumentException("paths 单项过长（≤" + MAX_PATH_LEN + "）: " + s);
            }
            return s;
        }).toList();
    }

    private static String resolveExpiry(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return Instant.now().plus(DEFAULT_EXPIRY_DAYS, ChronoUnit.DAYS).toString();
        }
        if ("never".equalsIgnoreCase(expiresAt.trim())) {
            return null;
        }
        Instant t = JdbcEvaluationSuppressionsRepository.parseInstant(expiresAt, "expires_at");
        if (t.isBefore(Instant.now())) {
            throw new IllegalArgumentException("expires_at 不能早于当前时间");
        }
        return t.toString();
    }

    private static String currentPrincipal() {
        var principal = ApiKeyAuthContext.current();
        return principal == null ? "local" : principal.credentialId();
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    /** 创建请求（snake_case 与 VE/前端约定一致）。 */
    public record CreateRequest(String id, String matchType, String riskId, String checkId,
                                List<String> paths, String reason, String sevAtAccept,
                                String expiresAt) {
    }
}

package io.virbius.control.admin;

import io.virbius.control.audit.AuditCenterService;
import io.virbius.control.audit.AuditExportService;
import io.virbius.control.audit.DailyAnchorJob;
import io.virbius.control.audit.DecisionBomService;
import io.virbius.control.audit.HashChainOrchestrator;
import io.virbius.control.audit.HashChainVerifier;
import io.virbius.control.audit.HashChainVerifier.ChainVerificationResult;
import io.virbius.control.audit.TraceChainVerifier;
import io.virbius.control.common.response.ApiResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/audit")
public class AuditAdminController {

    private final HashChainOrchestrator hashChain;
    private final HashChainVerifier verifier;
    private final AuditCenterService auditCenter;
    private final TraceChainVerifier traceChainVerifier;
    private final DecisionBomService decisionBom;
    private final AuditExportService auditExport;
    private final DailyAnchorJob dailyAnchorJob;

    public AuditAdminController(
            HashChainOrchestrator hashChain,
            HashChainVerifier verifier,
            AuditCenterService auditCenter,
            TraceChainVerifier traceChainVerifier,
            DecisionBomService decisionBom,
            AuditExportService auditExport,
            DailyAnchorJob dailyAnchorJob) {
        this.hashChain = hashChain;
        this.verifier = verifier;
        this.auditCenter = auditCenter;
        this.traceChainVerifier = traceChainVerifier;
        this.decisionBom = decisionBom;
        this.auditExport = auditExport;
        this.dailyAnchorJob = dailyAnchorJob;
    }

    // ============================================================
    // A1.2 hash chain + integrity
    // ============================================================

    @PostMapping("/verify")
    public ApiResult<ChainVerificationResult> verify(
            @PathVariable("tenantId") String tenantId,
            @RequestBody(required = false) VerifyRequest body) {
        ChainVerificationResult result;
        if (body != null && body.from() != null && body.to() != null) {
            result = verifier.verify(tenantId, Instant.parse(body.from()), Instant.parse(body.to()));
        } else {
            result = verifier.verifyAll(tenantId);
        }
        return ApiResult.ok(result);
    }

    @GetMapping("/chain/status")
    public ApiResult<HashChainOrchestrator.ChainHead> chainStatus(
            @PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(hashChain.getChainHead(tenantId));
    }

    /** A3: verify a session's trace chain, or the whole tenant when session_id is omitted. */
    @GetMapping("/trace-chain/verify")
    public ApiResult<Object> traceChainVerify(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "session_id", required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return ApiResult.ok(traceChainVerifier.verifySession(tenantId, sessionId));
        }
        return ApiResult.ok(traceChainVerifier.verifyTenant(tenantId));
    }

    /** A3: seal a day's Merkle anchor on demand (ops/backfill). */
    @PostMapping("/daily-anchor/seal")
    public ApiResult<Map<String, Object>> sealAnchor(
            @PathVariable("tenantId") String tenantId,
            @RequestBody(required = false) SealAnchorRequest body) {
        LocalDate day = body != null && body.date() != null && !body.date().isBlank()
                ? LocalDate.parse(body.date())
                : LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        DailyAnchorJob.SealResult result = dailyAnchorJob.seal(day);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sealed_date", day.toString());
        out.put("sealed", result.sealed());
        out.put("tenants_sealed", result.tenantsSealed());
        out.put("sessions", result.sessions());
        out.put("events", result.events());
        if (result.failed() > 0) {
            out.put("failed", result.failed());
        }
        if (result.anchors().size() == 1) {
            out.put("root_hash", result.anchors().get(0).rootHash());
        }
        out.put("anchors", result.anchors().stream()
                .map(a -> Map.of(
                        "tenant_id", a.tenantId(),
                        "root_hash", a.rootHash(),
                        "sessions", a.sessions(),
                        "events", a.events()))
                .toList());
        return ApiResult.ok(out);
    }

    /** A3: list the tenant's daily anchors (newest first). */
    @GetMapping("/daily-anchor")
    public ApiResult<List<Map<String, Object>>> listAnchors(
            @PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(dailyAnchorJob.list(tenantId));
    }

    /** A3: read one tenant-day anchor, or 404 when the day was never sealed. */
    @GetMapping("/daily-anchor/{date}")
    public ApiResult<?> getAnchor(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("date") String date) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (Exception e) {
            return ApiResult.error(400, "invalid_date");
        }
        Map<String, Object> anchor = dailyAnchorJob.get(tenantId, day);
        if (anchor == null) {
            return ApiResult.error(404, "not_found");
        }
        return ApiResult.ok(anchor);
    }

    // ============================================================
    // A6 Decision BOM / traceability export
    // ============================================================

    @GetMapping("/decision-bom/{traceId}")
    public ApiResult<Map<String, Object>> decisionBom(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("traceId") String traceId) {
        return ApiResult.ok(decisionBom.reconstruct(tenantId, traceId));
    }

    // ============================================================
    // A7 secure export
    // ============================================================

    @PostMapping("/export")
    public ApiResult<Map<String, Object>> export(
            @PathVariable("tenantId") String tenantId,
            @RequestBody ExportRequest body,
            @RequestParam(value = "requester", required = false) String requester) {
        AuditExportService.ExportFilters filters = new AuditExportService.ExportFilters(
                body != null ? body.from() : null,
                body != null ? body.to() : null,
                body != null ? body.sessionId() : null,
                body != null ? body.traceId() : null,
                body != null ? body.minRisk() : null,
                body != null ? body.effectiveAction() : null);
        return ApiResult.ok(auditExport.startExport(tenantId, requester, filters));
    }

    @GetMapping("/export")
    public ApiResult<List<Map<String, Object>>> listExports(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResult.ok(auditExport.list(tenantId, limit));
    }

    @GetMapping("/export/{jobId}")
    public ApiResult<?> exportStatus(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("jobId") String jobId) {
        Map<String, Object> job = auditExport.get(tenantId, jobId);
        if (job == null) {
            return ApiResult.error(404, "not_found");
        }
        return ApiResult.ok(job);
    }

    /** Download the encrypted artifact; the ledger row records who exported what. */
    @GetMapping("/export/{jobId}/file")
    public ResponseEntity<byte[]> exportFile(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("jobId") String jobId) throws Exception {
        byte[] bytes = auditExport.readFile(tenantId, jobId);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + jobId + ".bin\"")
                .header("X-Virbius-Format", "aes-256-gcm")
                .body(bytes);
    }

    public record VerifyRequest(String from, String to) {}

    public record SealAnchorRequest(String date) {}

    public record ExportRequest(
            String from, String to, String sessionId, String traceId,
            Integer minRisk, String effectiveAction) {}
}

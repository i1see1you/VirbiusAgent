package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.DelegatedCredentialService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delegated credential admin CRUD (v2.5-min, B1: config data, admin auth
 * inherited from the /api/v1/admin/** route policy).
 *
 * <p>The submitted credential value is registered into the request-scoped
 * redaction context BEFORE any validation runs (validation is manual, never
 * bean-validation, so binding cannot fire before registration), and the
 * exception advice never echoes request content. The value is never stored
 * in plaintext and never returned by any endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/delegated-credentials")
public class DelegatedCredentialAdminController {

    private final DelegatedCredentialService service;

    public DelegatedCredentialAdminController(DelegatedCredentialService service) {
        this.service = service;
    }

    public record CreateRequest(
            String handle, String host, String headerName, String headerPrefix, String value) {}

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(service.list(tenantId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody CreateRequest body) {
        DelegatedRedaction.register(body.value());
        try {
            return ApiResult.ok(service.create(tenantId, body.handle(), body.host(),
                    body.headerName(), body.headerPrefix(), body.value()));
        } finally {
            DelegatedRedaction.clear();
        }
    }

    @PutMapping("/{handle}")
    public ApiResult<Map<String, Object>> update(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("handle") String handle,
            @RequestBody CreateRequest body) {
        DelegatedRedaction.register(body.value());
        try {
            return ApiResult.ok(service.update(tenantId, handle, body.host(),
                    body.headerName(), body.headerPrefix(), body.value()));
        } finally {
            DelegatedRedaction.clear();
        }
    }

    @PostMapping("/{handle}/revoke")
    public ApiResult<Map<String, Object>> revoke(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("handle") String handle) {
        return ApiResult.ok(service.revoke(tenantId, handle));
    }

    @DeleteMapping("/{handle}")
    public ApiResult<Void> delete(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("handle") String handle) {
        service.delete(tenantId, handle);
        return ApiResult.ok();
    }

    /**
     * One-time KEK reveal for proxy deployment (audited, never logged).
     *
     * <p>Marked {@code Cache-Control: no-store} + {@code Pragma: no-cache} so no
     * intermediate proxy/browser caches the tenant KEK. Control records no
     * request/response bodies, so the KEK never reaches the access log; the
     * audit line emitted by the service carries only tenant/handle, never the
     * value.
     */
    @PostMapping("/kek/reveal")
    public ResponseEntity<ApiResult<Map<String, Object>>> revealKek(
            @PathVariable("tenantId") String tenantId) {
        String kek = service.revealKek(tenantId);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(ApiResult.ok(Map.of("kek_base64", kek, "note",
                        "store at proxy virbius.delegated.kek (0400 file recommended);"
                                + " phase 1 has no rotation — redeploy to change")));
    }
}

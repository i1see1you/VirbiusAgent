package io.virbius.control.admin;

import io.virbius.control.audit.HashChainOrchestrator;
import io.virbius.control.audit.HashChainVerifier;
import io.virbius.control.audit.HashChainVerifier.ChainVerificationResult;
import io.virbius.control.common.response.ApiResult;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/audit")
public class AuditAdminController {

    private final HashChainOrchestrator hashChain;
    private final HashChainVerifier verifier;

    public AuditAdminController(HashChainOrchestrator hashChain, HashChainVerifier verifier) {
        this.hashChain = hashChain;
        this.verifier = verifier;
    }

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

    public record VerifyRequest(String from, String to) {}
}

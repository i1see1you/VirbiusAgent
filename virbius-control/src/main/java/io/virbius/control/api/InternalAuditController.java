package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.audit.AuditAllowLogWriter;
import io.virbius.control.domain.dto.request.AuditEventsBatchRequest;
import io.virbius.policy.audit.AuditEventPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/internal/audit")
public class InternalAuditController {

    private final AuditEventPublisher publisher;
    private final AuditAllowLogWriter allowLogWriter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String ingestToken;
    private final int maxBatchSize;

    public InternalAuditController(
            AuditEventPublisher publisher,
            AuditAllowLogWriter allowLogWriter,
            @Value("${audit.ingest.http.token:}") String ingestToken,
            @Value("${audit.ingest.http.max-batch-size:100}") int maxBatchSize) {
        this.publisher = publisher;
        this.allowLogWriter = allowLogWriter;
        this.ingestToken = ingestToken != null ? ingestToken : "";
        this.maxBatchSize = maxBatchSize > 0 ? maxBatchSize : 100;
    }

    @PostMapping("/events")
    public Map<String, Object> ingestBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Audit-Token", required = false) String auditToken,
            @RequestBody AuditEventsBatchRequest body) {
        verifyToken(authorization, auditToken);
        if (body == null || body.events() == null || body.events().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "events required");
        }
        if (body.events().size() > maxBatchSize) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "batch too large");
        }
        int published = 0;
        int loggedAllow = 0;
        int rejected = 0;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> event : body.events()) {
            try {
                if (AuditAllowLogWriter.isAllowAction(event)) {
                    allowLogWriter.append(event);
                    loggedAllow++;
                    continue;
                }
                String json = mapper.writeValueAsString(event);
                publisher.publish(Map.of("payload", json));
                published++;
            } catch (Exception e) {
                rejected++;
                errors.add(e.getMessage() != null ? e.getMessage() : "publish failed");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("published", published);
        out.put("logged_allow", loggedAllow);
        out.put("rejected", rejected);
        out.put("queued_async", publisher.asyncEnabled());
        if (!errors.isEmpty()) {
            out.put("errors", errors);
        }
        return out;
    }

    private void verifyToken(String authorization, String auditToken) {
        if (ingestToken.isBlank()) {
            return;
        }
        String bearer = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : "";
        if (ingestToken.equals(bearer) || ingestToken.equals(auditToken)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid audit ingest token");
    }
}

package io.virbius.control.integrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/internal/integrate/tenants/{tenantId}/apps/{appId}")
public class ToolObservationController {

    private final ToolObservationIngestor ingestor;
    private final ObservationQueryService queryService;
    private final String ingestToken;
    private final int maxBatchSize;

    public ToolObservationController(
            ToolObservationIngestor ingestor,
            ObservationQueryService queryService,
            @Value("${integrate.ingest.token:}") String ingestToken,
            @Value("${integrate.ingest.max-batch-size:100}") int maxBatchSize) {
        this.ingestor = ingestor;
        this.queryService = queryService;
        this.ingestToken = ingestToken != null ? ingestToken : "";
        this.maxBatchSize = maxBatchSize > 0 ? maxBatchSize : 100;
    }

    @PostMapping("/tool-observations/batch")
    public Map<String, Object> ingestBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Integrate-Token", required = false) String integrateToken,
            @PathVariable String tenantId,
            @PathVariable String appId,
            @RequestBody Map<String, Object> body) {
        verifyToken(authorization, integrateToken);
        List<Map<String, Object>> events = eventsOf(body);
        if (events.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "events required");
        }
        if (events.size() > maxBatchSize) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "batch too large");
        }
        int accepted = ingestor.ingestEvents(tenantId, appId, events);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("queued_async", false);
        return out;
    }

    @GetMapping("/tool-observations")
    public Map<String, Object> query(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Integrate-Token", required = false) String integrateToken,
            @PathVariable String tenantId,
            @PathVariable String appId,
            @RequestParam(value = "call_id", required = false) String callId,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        verifyToken(authorization, integrateToken);
        List<Map<String, Object>> events = queryService.query(tenantId, appId, callId, since, until, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        out.put("count", events.size());
        out.put("note", "Phase A observations (advisory). Not hash-chained; not session risk input.");
        return out;
    }

    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Integrate-Token", required = false) String integrateToken,
            @PathVariable String tenantId,
            @PathVariable String appId,
            @RequestBody(required = false) Map<String, Object> body) {
        verifyToken(authorization, integrateToken);
        ingestor.upsertHeartbeat(tenantId, appId, body == null ? Map.of() : body);
        return Map.of("ok", true);
    }

    @PostMapping("/tool-inventory")
    public Map<String, Object> inventory(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Integrate-Token", required = false) String integrateToken,
            @PathVariable String tenantId,
            @PathVariable String appId,
            @RequestBody Map<String, Object> body) {
        verifyToken(authorization, integrateToken);
        List<Map<String, Object>> tools = toolsOf(body);
        int accepted = ingestor.upsertInventory(tenantId, appId, tools);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        return out;
    }

    @GetMapping("/liveness")
    public Map<String, Object> liveness(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Integrate-Token", required = false) String integrateToken,
            @PathVariable String tenantId,
            @PathVariable String appId) {
        verifyToken(authorization, integrateToken);
        List<Map<String, Object>> devices = queryService.liveness(tenantId, appId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", devices);
        out.put("count", devices.size());
        return out;
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Virbius-Integrate-Token", required = false) String integrateToken,
            @PathVariable String tenantId,
            @PathVariable String appId) {
        verifyToken(authorization, integrateToken);
        List<Map<String, Object>> tools = queryService.coverage(tenantId, appId);
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if ("B".equals(tool.get("recommended_mode")) && "A".equals(tool.get("bound_mode"))) {
                warnings.add(tool);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", tools);
        out.put("warnings", warnings);
        out.put("note", "warn-only: high/medium risk tools bound in mode A");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eventsOf(Map<String, Object> body) {
        Object events = body == null ? null : body.get("events");
        if (events instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolsOf(Map<String, Object> body) {
        Object tools = body == null ? null : body.get("tools");
        if (tools instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private void verifyToken(String authorization, String integrateToken) {
        if (ingestToken.isBlank()) {
            return;
        }
        String bearer = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : "";
        if (ingestToken.equals(bearer) || ingestToken.equals(integrateToken)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid integrate token");
    }
}

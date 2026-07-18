package io.virbius.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.eval.SessionRiskManager;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Internal API for receiving Falco alerts via {@code http_output}.
 *
 * <p>Falco sends syscall alerts as HTTP POST in its native JSON format. The
 * controller correlates each alert to an Agent session via a three-tier
 * lookup chain, then forwards it to {@link SessionRiskManager} for risk
 * scoring.
 *
 * <h3>Session correlation chain (ordered)</h3>
 * <ol>
 *   <li><b>Host PID</b> — {@code pid_trace:{proc.pid}} primary index.
 *       Fastest path; covers the Agent main process.</li>
 *   <li><b>Cgroup ID</b> — {@code cgroup_trace:{proc.cgroup.id}} reverse
 *       index. Survives fork/exec/detach within the same cgroup; covers
 *       grandchild processes and detached children where ppid fallback
 *       breaks. Preferred over ppid because cgroup is a container-level
 *       identity (stable across fork layers) while ppid is a process-level
 *       identity (breaks at depth > 1 or after setsid).</li>
 *   <li><b>Parent PID</b> — {@code pid_trace:{proc.ppid}} fallback.
 *       Last resort; only covers direct children whose parent is the
 *       registered Agent process.</li>
 * </ol>
 *
 * <p>Non-agent processes (none of the above match) are silently ignored —
 * this filters out system noise that Falco captures on the host.
 *
 * <p>Endpoint: {@code POST /api/internal/falco-alert}
 *
 * <h3>Falco JSON payload structure</h3>
 * <pre>{@code
 * {
 *   "output": "Sensitive file access (user=root, pid=12345, file=/etc/shadow)",
 *   "priority": "Warning",
 *   "rule": "Sensitive file access",
 *   "time": "2025-01-01T00:00:00.000000000Z",
 *   "output_fields": {
 *     "evt.time": 1704067200000000000,
 *     "proc.pid": 12345,
 *     "proc.ppid": 12300,
 *     "proc.cgroup.id": 777,
 *     "proc.cmdline": "/bin/cat /etc/shadow",
 *     "proc.pcmdline": "/agent/virbius-core",
 *     "user.name": "root"
 *   }
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/internal")
public class FalcoAlertController {

    private static final Logger log = LoggerFactory.getLogger(FalcoAlertController.class);

    /** Redis key prefix for pidmap entries, written by virbius-kernel/src/pidmap.rs. */
    private static final String PIDMAP_KEY_PREFIX = "pid_trace:";

    /** Redis key prefix for cgroup reverse index, written by pidmap.rs. */
    private static final String CGROUP_KEY_PREFIX = "cgroup_trace:";

    private final SessionRiskManager riskManager;
    private final Optional<JedisPool> jedisPool;
    private final ObjectMapper mapper;

    public FalcoAlertController(
            SessionRiskManager riskManager,
            Optional<JedisPool> jedisPool) {
        this.riskManager = riskManager;
        this.jedisPool = jedisPool;
        this.mapper = new ObjectMapper();
    }

    /**
     * Receive a Falco alert in native JSON format.
     *
     * <p>The alert is correlated to an Agent session via the three-tier
     * lookup chain (host_pid → cgroup_id → ppid), then forwarded to
     * {@link SessionRiskManager#onFalcoAlert(String)} which increments a
     * pending counter consumed by the next risk score update.
     *
     * @param falcoAlert the raw Falco JSON alert (output, output_fields, rule, priority, time)
     * @return a status map indicating whether the alert was processed or ignored
     */
    @PostMapping("/falco-alert")
    public Map<String, Object> onFalcoAlert(@RequestBody Map<String, Object> falcoAlert) {
        String rule = strVal(falcoAlert.get("rule"));
        String priority = strVal(falcoAlert.get("priority"));

        // ── 1. Extract host PID, cgroup ID, and ppid from Falco output_fields ──
        Object outputFieldsRaw = falcoAlert.get("output_fields");
        if (!(outputFieldsRaw instanceof Map<?, ?> outputFields)) {
            log.debug("falco alert without output_fields, ignoring: rule={}", rule);
            return Map.of("status", "ignored", "reason", "no_output_fields");
        }

        long hostPid = toLong(outputFields.get("proc.pid"));
        long cgroupId = toLong(outputFields.get("proc.cgroup.id"));
        long ppid = toLong(outputFields.get("proc.ppid"));

        if (hostPid <= 0) {
            log.debug("falco alert without proc.pid, ignoring: rule={}", rule);
            return Map.of("status", "ignored", "reason", "no_pid");
        }

        // ── 2. Three-tier session correlation: host_pid → cgroup → ppid ──
        String sessionId = lookupSessionByHostPid(hostPid);
        String resolvedBy = "pid";

        if (sessionId == null && cgroupId > 0) {
            // Cgroup correlation: covers grandchild processes, detached
            // children (setsid), and exec'd processes within the same cgroup.
            // Preferred over ppid because cgroup is container-level (stable
            // across fork layers) while ppid breaks at depth > 1.
            sessionId = lookupSessionByCgroup(cgroupId);
            if (sessionId != null) {
                resolvedBy = "cgroup";
                log.debug("falco alert pid={} resolved via cgroup={} to session={}",
                        hostPid, cgroupId, sessionId);
            }
        }

        if (sessionId == null && ppid > 0) {
            // Parent PID fallback: only covers direct children whose parent
            // is the registered Agent process. Last resort.
            sessionId = lookupSessionByHostPid(ppid);
            if (sessionId != null) {
                resolvedBy = "ppid";
                log.debug("falco alert pid={} resolved via ppid={} to session={}",
                        hostPid, ppid, sessionId);
            }
        }

        if (sessionId == null) {
            log.debug("falco alert pid={} (cgroup={}, ppid={}) not mapped, ignoring: rule={}",
                    hostPid, cgroupId, ppid, rule);
            return Map.of("status", "ignored", "reason", "pid_not_mapped");
        }

        // ── 3. Forward to risk manager ──
        riskManager.onFalcoAlert(sessionId);
        log.info("falco alert received: session={} pid={} cgroup={} ppid={} resolved_by={} rule={} priority={}",
                sessionId, hostPid, cgroupId, ppid, resolvedBy, rule, priority);
        return Map.of("status", "ok", "session_id", sessionId, "resolved_by", resolvedBy);
    }

    /**
     * Lookup session_id from Redis pidmap (primary index).
     *
     * <p>Key format: {@code pid_trace:{host_pid}} — written by the Rust pidmap
     * module ({@code virbius-kernel/src/pidmap.rs}) on agent registration via
     * {@code register_agent()}. The value is a JSON object containing
     * {@code session_id}, {@code trace_id}, {@code app_id}, etc.
     *
     * <p>Falco's {@code proc.pid} is the <strong>Host PID</strong> (visible in
     * the initial PID namespace), which matches the key written by pidmap.
     *
     * @param hostPid the host PID from Falco's {@code proc.pid} or {@code proc.ppid} field
     * @return the session ID, or {@code null} if not found or Redis unavailable
     */
    private String lookupSessionByHostPid(long hostPid) {
        if (jedisPool.isEmpty()) {
            log.debug("Redis not configured, cannot lookup pidmap for pid={}", hostPid);
            return null;
        }
        try (Jedis jedis = jedisPool.get().getResource()) {
            String val = jedis.get(PIDMAP_KEY_PREFIX + hostPid);
            if (val == null) {
                return null;
            }
            JsonNode node = mapper.readTree(val);
            String sessionId = node.path("session_id").asText(null);
            return (sessionId != null && !sessionId.isBlank()) ? sessionId : null;
        } catch (Exception e) {
            log.warn("pidmap lookup failed for pid={}: {}", hostPid, e.getMessage());
            return null;
        }
    }

    /**
     * Lookup session_id from Redis cgroup reverse index.
     *
     * <p>Key format: {@code cgroup_trace:{cgroup_id}} — written by
     * {@code pidmap.rs::redis_backup_async()} alongside the primary
     * {@code pid_trace:{host_pid}} key. Both keys point to the same JSON
     * value, so the session_id extraction logic is identical.
     *
     * <p>The cgroup index survives fork/exec/detach within the same cgroup,
     * making it the preferred fallback when the direct PID lookup misses:
     * <ul>
     *   <li>Grandchild processes (depth > 1 from Agent main)</li>
     *   <li>Detached children (setsid/nohup, ppid → init)</li>
     *   <li>Containerized Agents where ppid points to container init</li>
     * </ul>
     *
     * <p>Only available on cgroup v2 + Falco 0.37+ (modern eBPF driver).
     * When {@code proc.cgroup.id} is absent or 0, this method is skipped
     * and the caller falls back to ppid.
     *
     * @param cgroupId the cgroup v2 inode ID from Falco's {@code proc.cgroup.id}
     * @return the session ID, or {@code null} if not found or Redis unavailable
     */
    private String lookupSessionByCgroup(long cgroupId) {
        if (jedisPool.isEmpty()) {
            log.debug("Redis not configured, cannot lookup cgroup for cgroup={}", cgroupId);
            return null;
        }
        try (Jedis jedis = jedisPool.get().getResource()) {
            String val = jedis.get(CGROUP_KEY_PREFIX + cgroupId);
            if (val == null) {
                return null;
            }
            JsonNode node = mapper.readTree(val);
            String sessionId = node.path("session_id").asText(null);
            return (sessionId != null && !sessionId.isBlank()) ? sessionId : null;
        } catch (Exception e) {
            log.warn("cgroup lookup failed for cgroup={}: {}", cgroupId, e.getMessage());
            return null;
        }
    }

    private static String strVal(Object o) {
        return o != null ? o.toString() : null;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}

package io.virbius.engine.api;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Best-effort in-memory rate limiter for {@code POST /v1/evaluate}.
 *
 * <p>The endpoint is unauthenticated on the internal network, and
 * {@code role=input} reviews can raise a session's risk score — an
 * unthrottled client could therefore inflate arbitrary sessions (or simply
 * burn guard-model capacity). This limiter bounds that abuse vector.
 *
 * <p>Quota model (v2 — must not throttle legitimate streaming review):
 * <ul>
 *   <li><b>Per-trace aggregation</b>: {@code stream_chunk=true} calls sharing a
 *       {@code trace_id} consume ONE session-quota unit for the whole answer
 *       (windows are the review of a single answer, not separate requests).</li>
 *   <li><b>Per-trace window cap</b>: a single trace may not exceed
 *       {@code max-windows-per-trace} review windows (guards against a bogus
 *       trace streaming forever).</li>
 *   <li><b>Per-session quota</b>: answers/requests per minute, keyed by
 *       {@code session_id} (falls back to the client IP when absent).</li>
 *   <li><b>Per-IP quota</b>: total calls per minute — the loose backstop that
 *       covers callers that omit both session and trace.</li>
 * </ul>
 *
 * <p>Fixed one-minute buckets, in-process (approximate under concurrency —
 * acceptable for abuse bounding, not billing).
 */
@Component
public class EvaluateRateLimiter {

    private final boolean enabled;
    private final long perSessionPerMin;
    private final long perIpPerMin;
    private final long maxWindowsPerTrace;
    private final long perAgentPerMin;

    private final ConcurrentHashMap<String, MinuteCounter> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MinuteCounter> ips = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MinuteCounter> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TraceState> traces = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluateRateLimiter(
            @Value("${virbius.evaluate.rate-limit.enabled:true}") boolean enabled,
            @Value("${virbius.evaluate.rate-limit.per-session-per-min:60}") long perSessionPerMin,
            @Value("${virbius.evaluate.rate-limit.per-ip-per-min:600}") long perIpPerMin,
            @Value("${virbius.evaluate.rate-limit.max-windows-per-trace:64}") long maxWindowsPerTrace,
            @Value("${virbius.evaluate.rate-limit.per-agent-per-min:600}") long perAgentPerMin) {
        this.enabled = enabled;
        this.perSessionPerMin = Math.max(1, perSessionPerMin);
        this.perIpPerMin = Math.max(1, perIpPerMin);
        this.maxWindowsPerTrace = Math.max(1, maxWindowsPerTrace);
        this.perAgentPerMin = Math.max(1, perAgentPerMin);
    }

    /** Test/legacy constructor: agent-level backstop defaults to 10x session quota. */
    public EvaluateRateLimiter(
            boolean enabled, long perSessionPerMin, long perIpPerMin, long maxWindowsPerTrace) {
        this(enabled, perSessionPerMin, perIpPerMin, maxWindowsPerTrace, perSessionPerMin * 10);
    }

    /**
     * @return empty when the call is allowed; otherwise the suggested
     *         {@code Retry-After} duration.
     */
    public java.util.Optional<Duration> check(String sessionId, String ip, String traceId, boolean streamChunk) {
        return check(sessionId, ip, traceId, streamChunk, false);
    }

    /**
     * Authenticated variant (P0-min). When {@code authenticated}, {@code sessionId}
     * carries the VERIFIED agent_aid: the session-layer key becomes agent-level
     * (B3: an agent-level backstop on top of the unchanged per-session quota —
     * a multi-session agent cannot escape its agent quota by rotating sessions,
     * and legitimate multi-session agents are not throttled by the session
     * quota alone). The per-agent limit defaults to 10x the session limit.
     */
    public java.util.Optional<Duration> check(
            String sessionId, String ip, String traceId, boolean streamChunk, boolean authenticated) {
        if (!enabled) {
            return java.util.Optional.empty();
        }
        long minute = System.currentTimeMillis() / 60_000L;

        // IP backstop: every call counts.
        MinuteCounter ipCounter = ips.computeIfAbsent(ip == null || ip.isBlank() ? "unknown" : ip,
                k -> new MinuteCounter());
        if (ipCounter.increment(minute) > perIpPerMin) {
            return java.util.Optional.of(remaining(minute));
        }

        // Agent-level backstop (verified subject; B3).
        if (authenticated && sessionId != null && !sessionId.isBlank()) {
            MinuteCounter agentCounter = agents.computeIfAbsent(
                    "agent:" + sessionId, k -> new MinuteCounter());
            if (agentCounter.increment(minute) > perAgentPerMin) {
                return java.util.Optional.of(remaining(minute));
            }
        }

        String sessionKey = sessionId == null || sessionId.isBlank()
                ? "ip:" + (ip == null || ip.isBlank() ? "unknown" : ip)
                : (authenticated ? "agent:" : "sess:") + sessionId;
        MinuteCounter sessionCounter = sessions.computeIfAbsent(sessionKey, k -> new MinuteCounter());

        if (streamChunk && traceId != null && !traceId.isBlank()) {
            // Windows of one answer share a single session-quota unit.
            TraceState trace = traces.computeIfAbsent(traceId, k -> new TraceState());
            if (trace.chargeSessionQuota(minute, sessionCounter)) {
                if (sessionCounter.getCount(minute) > perSessionPerMin) {
                    return java.util.Optional.of(remaining(minute));
                }
            }
            if (trace.incrementWindows() > maxWindowsPerTrace) {
                return java.util.Optional.of(remaining(minute));
            }
            return java.util.Optional.empty();
        }

        // Non-streaming call (input review, whole-answer output review), or a
        // stream review without a trace — each call is one session unit.
        if (sessionCounter.increment(minute) > perSessionPerMin) {
            return java.util.Optional.of(remaining(minute));
        }
        return java.util.Optional.empty();
    }

    private static Duration remaining(long minute) {
        long nextMinuteMs = (minute + 1) * 60_000L;
        long wait = Math.max(1L, nextMinuteMs - System.currentTimeMillis());
        return Duration.ofMillis(wait);
    }

    /** Fixed one-minute bucket counter with lazy reset. */
    static final class MinuteCounter {
        private volatile long minute = -1;
        private final AtomicLong count = new AtomicLong();

        long increment(long currentMinute) {
            resetIfNeeded(currentMinute);
            return count.incrementAndGet();
        }

        long getCount(long currentMinute) {
            resetIfNeeded(currentMinute);
            return count.get();
        }

        private void resetIfNeeded(long currentMinute) {
            if (minute != currentMinute) {
                synchronized (this) {
                    if (minute != currentMinute) {
                        count.set(0);
                        minute = currentMinute;
                    }
                }
            }
        }
    }

    /** Per-trace window bookkeeping (bounded map; swept on size pressure). */
    static final class TraceState {
        private final AtomicLong windows = new AtomicLong();
        private volatile long quotaMinute = -1;
        private volatile long lastAccess = System.currentTimeMillis();

        /** Charge the session quota at most once per trace (whole answer = one unit). */
        boolean chargeSessionQuota(long minute, MinuteCounter sessionCounter) {
            if (quotaMinute == minute || quotaMinute >= minute) {
                return false;
            }
            synchronized (this) {
                if (quotaMinute >= minute) {
                    return false;
                }
                quotaMinute = minute;
            }
            sessionCounter.increment(minute);
            return true;
        }

        long incrementWindows() {
            lastAccess = System.currentTimeMillis();
            return windows.incrementAndGet();
        }
    }

    /** Drop state for traces idle beyond TTL when the map grows large. */
    void evictIdle() {
        if (traces.size() < 16384) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 600_000L;
        for (Iterator<Map.Entry<String, TraceState>> it = traces.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().lastAccess < cutoff) {
                it.remove();
            }
        }
    }
}

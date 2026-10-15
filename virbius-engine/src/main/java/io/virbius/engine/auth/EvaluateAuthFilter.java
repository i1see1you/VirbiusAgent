package io.virbius.engine.auth;

import io.virbius.engine.auth.TrustedIssuerRegistry.IssuedTokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication for the engine's decision endpoints (P0-min).
 *
 * <p>Applies to {@code /v1/evaluate}, {@code /v1/evaluate/tool-result},
 * {@code /v1/memory/check} and {@code /v1/challenge/verify}. Requests carry a
 * short-lived Bearer token ({@code aud=evaluate}) issued by control; the
 * long-lived License JWT is never accepted here.
 *
 * <p><b>Fail-closed</b>: a missing/invalid/expired/revoked token is a 401 and
 * the request never reaches the pipeline — mirroring the 429 policy, an auth
 * failure must never degrade to "content passes unreviewed" (AGT design
 * principle 4).
 *
 * <p>Pre-auth per-IP rate limiting (C3): unauthenticated 401 traffic also
 * consumes quota so signature-spam cannot DoS the filter.
 */
@Component
@Order(2)
public class EvaluateAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EvaluateAuthFilter.class);

    /** Request attribute carrying the verified subject (controller reads it). */
    public static final String SUBJECT_ATTRIBUTE = "virbius.verified-subject";

    private static final java.util.Set<String> PROTECTED_PATHS = java.util.Set.of(
            "/v1/evaluate",
            "/v1/evaluate/tool-result",
            "/v1/memory/check",
            "/v1/challenge/verify");

    private final TrustedIssuerRegistry issuers;
    private final RevocationCache revocations;

    private final boolean enabled;
    private final String bootstrapPemFile;
    private final String bootstrapKid;

    /** Fixed one-minute IP counter for pre-auth limiting (C3). */
    private final Map<String, long[]> ipCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final long preAuthIpPerMin;

    public EvaluateAuthFilter(
            TrustedIssuerRegistry issuers,
            RevocationCache revocations,
            @Value("${virbius.evaluate.auth.enabled:true}") boolean enabled,
            @Value("${virbius.evaluate.auth.bootstrap-pem-file:}") String bootstrapPemFile,
            @Value("${virbius.evaluate.auth.bootstrap-kid:}") String bootstrapKid,
            @Value("${virbius.evaluate.rate-limit.per-ip-per-min:600}") long preAuthIpPerMin) {
        this.issuers = issuers;
        this.revocations = revocations;
        this.enabled = enabled;
        this.bootstrapPemFile = bootstrapPemFile;
        this.bootstrapKid = bootstrapKid;
        this.preAuthIpPerMin = preAuthIpPerMin;
    }

    /** Boot: anchor (must succeed when auth is enabled) + Redis enrichment + revocation cache. */
    @jakarta.annotation.PostConstruct
    public void init() {
        if (!enabled) {
            log.warn("evaluate auth DISABLED by config — /v1/evaluate is unauthenticated");
            return;
        }
        // N1: the config-baked anchor is the guaranteed trust root.
        if (bootstrapPemFile != null && !bootstrapPemFile.isBlank()) {
            try {
                String pem = Files.readString(Path.of(bootstrapPemFile));
                String kid = (bootstrapKid == null || bootstrapKid.isBlank())
                        ? "bootstrap" : bootstrapKid;
                issuers.registerBootstrapKey(kid, pem);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "evaluate auth enabled but bootstrap PEM unreadable (" + bootstrapPemFile
                                + "): " + e.getMessage(), e);
            }
        }
        // Redis enrichment (bounded retry, never fatal).
        issuers.loadFromRedisWithRetry();
        if (!issuers.hasAnyKey()) {
            throw new IllegalStateException(
                    "evaluate auth enabled but no verification key available "
                            + "(neither bootstrap PEM nor Redis issuers) — refusing to start unauthenticated");
        }
        revocations.start();
        log.info("evaluate auth active (issuers={}, revocations subscriber started)", issuers.keyCount());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !PROTECTED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request);

        // C3: pre-auth IP quota covers 401-spam (signature verification costs CPU).
        if (overPreAuthIpLimit(ip)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            writeError(response, "rate_limited", "pre-auth ip quota exceeded");
            return;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "missing_token");
            return;
        }
        String token = auth.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, "missing_token");
            return;
        }

        TrustedIssuerRegistry.IssuedTokenClaims claims =
                issuers.verify(token, "evaluate").orElse(null);
        if (claims == null) {
            writeUnauthorized(response, "invalid_token");
            return;
        }

        // B1: revocation is checked on EVERY request (never cached with the signature).
        if (revocations.isRevoked(claims.agentAid(), claims.jti())) {
            writeUnauthorized(response, "revoked");
            return;
        }

        // sid binding: a sid-bound token is valid ONLY for that session (N3).
        if (claims.sid() != null && !claims.sid().isBlank()) {
            String requestSession = request.getParameter("session_id");
            if (requestSession == null) {
                // The body carries session_id; the controller re-checks after
                // deserialization — here we can only enforce header/query presence.
                request.setAttribute("virbius.required-sid", claims.sid());
            } else if (!claims.sid().equals(requestSession)) {
                writeUnauthorized(response, "sid_mismatch");
                return;
            }
        }

        request.setAttribute(SUBJECT_ATTRIBUTE, claims);
        chain.doFilter(request, response);
    }

    /** Post-deserialization sid check (called by the controller). */
    public static String requiredSid(HttpServletRequest request) {
        Object sid = request.getAttribute("virbius.required-sid");
        return sid == null ? null : sid.toString();
    }

    public static IssuedTokenClaims subject(HttpServletRequest request) {
        Object s = request.getAttribute(SUBJECT_ATTRIBUTE);
        return s instanceof IssuedTokenClaims claims ? claims : null;
    }

    private boolean overPreAuthIpLimit(String ip) {
        long minute = System.currentTimeMillis() / 60_000L;
        long[] counter = ipCounters.computeIfAbsent(ip, k -> new long[]{minute, 0});
        synchronized (counter) {
            if (counter[0] != minute) {
                counter[0] = minute;
                counter[1] = 0;
            }
            counter[1]++;
            return counter[1] > preAuthIpPerMin;
        }
    }

    private static void writeUnauthorized(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(401);
        writeError(response, "unauthorized", reason);
    }

    private static void writeError(
            HttpServletResponse response, String error, String reason) throws IOException {
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("reason", reason);
        response.getWriter().write(jsonOf(body));
    }

    private static String jsonOf(Map<String, Object> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            return "{\"error\":\"unauthorized\"}";
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}

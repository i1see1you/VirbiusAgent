package io.virbius.control.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Resolves the human operator behind a mutating admin request.
 *
 * <p>Identity chain: explicit client-supplied value (e.g. an "on behalf of"
 * entry) → {@code X-User-Id} header (edge/proxy injected) → the authenticated
 * principal → {@code fallback} (never a hard-coded pretend identity such as
 * {@code "operator"}). Every admin mutation records the resolved value so the
 * audit trail names a real actor or an honest {@code "unknown"}.
 */
public final class OperatorResolver {

    private OperatorResolver() {
    }

    /** Explicit value wins; otherwise resolve from the request, else {@code fallback}. */
    public static String resolve(String explicit, HttpServletRequest request, String fallback) {
        if (StringUtils.hasText(explicit)) {
            return explicit.trim();
        }
        return resolve(request, fallback);
    }

    /** {@code X-User-Id} header → authenticated principal → {@code fallback}. */
    public static String resolve(HttpServletRequest request, String fallback) {
        if (request != null) {
            String user = request.getHeader("X-User-Id");
            if (StringUtils.hasText(user)) {
                return user.trim();
            }
            String remote = request.getRemoteUser();
            if (StringUtils.hasText(remote)) {
                return remote.trim();
            }
        }
        return fallback;
    }
}

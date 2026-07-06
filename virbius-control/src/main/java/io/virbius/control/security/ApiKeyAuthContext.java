package io.virbius.control.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class ApiKeyAuthContext {

    public static final String REQUEST_ATTR = "virbius.api.auth";

    private ApiKeyAuthContext() {}

    public static void set(HttpServletRequest request, ApiKeyPrincipal principal) {
        request.setAttribute(REQUEST_ATTR, principal);
    }

    public static ApiKeyPrincipal current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            Object v = servlet.getRequest().getAttribute(REQUEST_ATTR);
            if (v instanceof ApiKeyPrincipal p) {
                return p;
            }
        }
        return null;
    }

    public static ApiKeyPrincipal require() {
        ApiKeyPrincipal p = current();
        if (p == null) {
            throw new IllegalStateException("api key auth context missing");
        }
        return p;
    }
}

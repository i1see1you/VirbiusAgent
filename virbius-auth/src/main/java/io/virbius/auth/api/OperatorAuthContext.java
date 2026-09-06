package io.virbius.auth.api;

import io.virbius.auth.domain.OperatorUser;
import jakarta.servlet.http.HttpServletRequest;

public final class OperatorAuthContext {

    public static final String ATTR = "virbius.auth.operator";

    private OperatorAuthContext() {}

    public static void set(HttpServletRequest request, OperatorUser user) {
        request.setAttribute(ATTR, user);
    }

    public static OperatorUser get(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR);
        return v instanceof OperatorUser u ? u : null;
    }
}

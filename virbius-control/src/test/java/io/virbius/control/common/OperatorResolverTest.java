package io.virbius.control.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OperatorResolverTest {

    @Test
    void explicitValueWinsOverRequestIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "header-user");

        assertEquals("on-behalf", OperatorResolver.resolve(" on-behalf ", request, "unknown"));
    }

    @Test
    void blankExplicitFallsBackToHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "header-user");

        assertEquals("header-user", OperatorResolver.resolve("  ", request, "unknown"));
    }

    @Test
    void fallsBackToAuthenticatedPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteUser("remote-user");

        assertEquals("remote-user", OperatorResolver.resolve(null, request, "unknown"));
    }

    @Test
    void fallsBackToCallerSuppliedDefaultWhenNoIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals("unknown", OperatorResolver.resolve(null, request, "unknown"));
    }

    @Test
    void nullRequestYieldsFallback() {
        assertEquals("system", OperatorResolver.resolve(null, null, "system"));
    }
}

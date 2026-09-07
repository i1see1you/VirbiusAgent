package io.virbius.control.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.api.ApiKeyAuthFilter;
import io.virbius.control.security.OperatorJwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UiAuthControllerTest {

    @Test
    void missingCodeOrMismatchedStateDoesNotSetCookie() throws Exception {
        OperatorJwtProperties props = new OperatorJwtProperties();
        props.setTokenUrl("http://127.0.0.1:9/oauth/token");
        UiAuthController c = new UiAuthController(props, new ObjectMapper());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(ApiKeyAuthFilter.LOGIN_STATE_COOKIE, "abc"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        c.callback(null, "abc", req, res);
        assertEquals(400, res.getStatus());
        assertNull(res.getCookie(ApiKeyAuthFilter.OPERATOR_COOKIE));
        String html = res.getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("登录状态无效"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("role=\"alert\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("重新登录"));

        res = new MockHttpServletResponse();
        c.callback("code", "nope", req, res);
        assertEquals(400, res.getStatus());
        assertNull(res.getCookie(ApiKeyAuthFilter.OPERATOR_COOKIE));
        org.junit.jupiter.api.Assertions.assertTrue(res.getContentAsString().contains("登录状态无效"));
    }

    @Test
    void redeemFailureDoesNotSetCookie() throws Exception {
        OperatorJwtProperties props = new OperatorJwtProperties();
        props.setTokenUrl("http://127.0.0.1:9/oauth/token");
        UiAuthController c = new UiAuthController(props, new ObjectMapper());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(ApiKeyAuthFilter.LOGIN_STATE_COOKIE, "abc"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        c.callback("code", "abc", req, res);
        assertEquals(401, res.getStatus());
        assertNull(res.getCookie(ApiKeyAuthFilter.OPERATOR_COOKIE));
        org.junit.jupiter.api.Assertions.assertTrue(res.getContentAsString().contains("登录凭证交换失败"));
    }

    @Test
    void logoutClearsOperatorCookie() {
        UiAuthController c = new UiAuthController(new OperatorJwtProperties(), new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        c.logout(new MockHttpServletRequest(), res);
        assertEquals(204, res.getStatus());
        String setCookie = res.getHeader("Set-Cookie");
        org.junit.jupiter.api.Assertions.assertTrue(setCookie.contains(ApiKeyAuthFilter.OPERATOR_COOKIE));
        org.junit.jupiter.api.Assertions.assertTrue(setCookie.contains("Max-Age=0"));
    }
}

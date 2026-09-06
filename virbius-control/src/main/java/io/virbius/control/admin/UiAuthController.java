package io.virbius.control.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.api.ApiKeyAuthFilter;
import io.virbius.control.security.OperatorJwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class UiAuthController {

    private final OperatorJwtProperties jwtProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public UiAuthController(OperatorJwtProperties jwtProperties, ObjectMapper objectMapper) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/ui/callback")
    public void callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        String expected = ApiKeyAuthFilter.cookieValue(request, ApiKeyAuthFilter.LOGIN_STATE_COOKIE);
        if (code == null || code.isBlank() || state == null || expected.isBlank() || !expected.equals(state)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String token = redeem(code);
        if (token == null || token.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        boolean secure = request.isSecure();
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(ApiKeyAuthFilter.OPERATOR_COOKIE, token)
                        .httpOnly(true)
                        .path("/")
                        .sameSite("Lax")
                        .secure(secure)
                        .maxAge(Duration.ofMinutes(60))
                        .build()
                        .toString());
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(ApiKeyAuthFilter.LOGIN_STATE_COOKIE, "")
                        .httpOnly(true)
                        .path("/")
                        .maxAge(0)
                        .build()
                        .toString());
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, "/ui/");
    }

    @PostMapping("/ui/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(ApiKeyAuthFilter.OPERATOR_COOKIE, "")
                        .httpOnly(true)
                        .path("/")
                        .maxAge(0)
                        .secure(request.isSecure())
                        .build()
                        .toString());
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private String redeem(String code) {
        try {
            String body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(jwtProperties.getTokenUrl()))
                    .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            JsonNode node = objectMapper.readTree(res.body());
            JsonNode token = node.get("access_token");
            return token == null || token.isNull() ? null : token.asText();
        } catch (Exception e) {
            return null;
        }
    }
}

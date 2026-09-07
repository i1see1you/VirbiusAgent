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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class UiAuthController {

    private static final Logger log = LoggerFactory.getLogger(UiAuthController.class);

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
        boolean codeBlank = code == null || code.isBlank();
        boolean stateBlank = state == null || state.isBlank();
        boolean cookieBlank = expected.isBlank();
        boolean mismatch = !cookieBlank && !stateBlank && !expected.equals(state);
        if (codeBlank || stateBlank || cookieBlank || mismatch) {
            log.warn(
                    "ui callback rejected: codeBlank={} stateBlank={} cookieBlank={} mismatch={}",
                    codeBlank,
                    stateBlank,
                    cookieBlank,
                    mismatch);
            fail(response, HttpServletResponse.SC_BAD_REQUEST, "登录状态无效，请重新登录。");
            return;
        }
        String token = redeem(code);
        if (token == null || token.isBlank()) {
            log.warn("ui callback rejected: authorization code exchange failed");
            fail(response, HttpServletResponse.SC_UNAUTHORIZED, "登录凭证交换失败，请重试。");
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

    private static void fail(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(ERROR_PAGE.replace("{{MESSAGE}}", escape(message)));
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static final String ERROR_PAGE =
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>登录未完成</title>
            <style>
            :root {
              --bg: #f1f5f9;
              --card: #fff;
              --border: #e2e8f0;
              --text: #0f172a;
              --muted: #64748b;
              --primary: #2563eb;
              --primary-hover: #1d4ed8;
              --err-bg: #fee2e2;
              --err-text: #991b1b;
              --radius: 8px;
            }
            * { box-sizing: border-box; }
            html, body { margin: 0; height: 100%; }
            body {
              min-height: 100vh;
              display: flex;
              align-items: center;
              justify-content: center;
              padding: 20px;
              font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
              font-size: 13px;
              color: var(--text);
              background: var(--bg);
            }
            .card {
              width: 100%;
              max-width: 400px;
              background: var(--card);
              border: 1px solid var(--border);
              border-radius: var(--radius);
              padding: 32px;
              box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
            }
            .brand {
              display: flex;
              flex-direction: column;
              align-items: center;
              text-align: center;
              margin-bottom: 24px;
              gap: 10px;
            }
            .mark {
              width: 36px;
              height: 36px;
              border-radius: var(--radius);
              background: #0f172a;
              display: grid;
              place-items: center;
            }
            h1 { margin: 0; font-size: 18px; font-weight: 600; }
            .sub { margin: 0; color: var(--muted); }
            .err {
              margin: 0 0 20px;
              padding: 10px 12px;
              border-radius: 6px;
              background: var(--err-bg);
              color: var(--err-text);
              text-align: center;
            }
            a.cta {
              display: flex;
              align-items: center;
              justify-content: center;
              height: 40px;
              border-radius: var(--radius);
              background: var(--primary);
              color: #fff;
              font-weight: 600;
              text-decoration: none;
              cursor: pointer;
              transition: background 0.2s ease, box-shadow 0.2s ease;
            }
            a.cta:hover { background: var(--primary-hover); }
            a.cta:focus-visible {
              outline: none;
              box-shadow: 0 0 0 2px #fff, 0 0 0 4px var(--primary);
            }
            @media (prefers-reduced-motion: reduce) {
              a.cta { transition: none; }
            }
            </style>
            </head>
            <body>
            <main class="card">
              <div class="brand">
                <div class="mark" aria-hidden="true">
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
                    <path d="M9 2.2 15 4.6v4.4c0 3.6-2.5 5.9-6 7.3-3.5-1.4-6-3.7-6-7.3V4.6L9 2.2Z" fill="#2563eb"/>
                    <path d="M9 6.2v6.2" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>
                  </svg>
                </div>
                <h1>登录未完成</h1>
                <p class="sub">需要一次完整的运营台登录流程</p>
              </div>
              <p class="err" role="alert">{{MESSAGE}}</p>
              <a class="cta" href="/ui/">重新登录</a>
            </main>
            </body>
            </html>
            """;

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
                log.warn("token exchange HTTP {}", res.statusCode());
                return null;
            }
            JsonNode node = objectMapper.readTree(res.body());
            JsonNode token = node.get("access_token");
            return token == null || token.isNull() ? null : token.asText();
        } catch (Exception e) {
            log.warn("token exchange failed: {}", e.toString());
            return null;
        }
    }
}

package io.virbius.auth.api;

import io.virbius.auth.service.LoginService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class LoginController {

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> loginPage(
            @RequestParam(name = "return_uri") String returnUri, @RequestParam(name = "state") String state) {
        if (!loginService.isReturnUriAllowed(returnUri)) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("invalid return_uri");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(form(returnUri, state, null));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> login(@RequestParam MultiValueMap<String, String> form) {
        String returnUri = form.getFirst("return_uri");
        String state = form.getFirst("state");
        if (!loginService.isReturnUriAllowed(returnUri)) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("invalid return_uri");
        }
        var code = loginService.login(form.getFirst("username"), form.getFirst("password"), returnUri);
        if (code.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.TEXT_HTML)
                    .body(form(returnUri, state, LoginService.GENERIC_FAILURE));
        }
        URI redirect = UriComponentsBuilder.fromUriString(returnUri)
                .queryParam("code", code.get())
                .queryParam("state", state == null ? "" : state)
                .build(true)
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
    }

    private static String form(String returnUri, String state, String error) {
        String err = error == null
                ? ""
                : "<p class=\"err\" role=\"alert\">用户名或密码不正确</p>";
        return TEMPLATE
                .replace("{{RETURN_URI}}", escape(returnUri))
                .replace("{{STATE}}", escape(state))
                .replace("{{ERROR}}", err);
    }

    private static final String TEMPLATE =
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>Virbius 运营登录</title>
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
            .mark svg { display: block; }
            h1 {
              margin: 0;
              font-size: 18px;
              font-weight: 600;
              letter-spacing: 0.01em;
            }
            .sub { margin: 0; color: var(--muted); font-size: 13px; }
            .err {
              margin: 0 0 16px;
              padding: 10px 12px;
              border-radius: 6px;
              background: var(--err-bg);
              color: var(--err-text);
              text-align: center;
            }
            form { display: flex; flex-direction: column; gap: 16px; }
            .field { display: flex; flex-direction: column; gap: 6px; }
            label { font-weight: 600; color: #334155; }
            input[type="text"], input[type="password"] {
              width: 100%;
              height: 40px;
              padding: 0 12px;
              border: 1px solid var(--border);
              border-radius: var(--radius);
              font: inherit;
              color: var(--text);
              background: #fff;
            }
            input:focus {
              outline: none;
              border-color: var(--primary);
              box-shadow: 0 0 0 2px #bfdbfe;
            }
            button {
              height: 40px;
              margin-top: 8px;
              border: 0;
              border-radius: var(--radius);
              background: var(--primary);
              color: #fff;
              font: inherit;
              font-weight: 600;
              cursor: pointer;
              transition: background 0.2s ease, box-shadow 0.2s ease;
            }
            button:hover { background: var(--primary-hover); }
            button:focus-visible {
              outline: none;
              box-shadow: 0 0 0 2px #fff, 0 0 0 4px var(--primary);
            }
            @media (prefers-reduced-motion: reduce) {
              button { transition: none; }
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
                <h1>Virbius 运营登录</h1>
                <p class="sub">使用运营账号进入控制台</p>
              </div>
              {{ERROR}}
              <form method="post" action="/login">
                <input type="hidden" name="return_uri" value="{{RETURN_URI}}"/>
                <input type="hidden" name="state" value="{{STATE}}"/>
                <div class="field">
                  <label for="username">用户名</label>
                  <input id="username" name="username" type="text" autocomplete="username" autofocus/>
                </div>
                <div class="field">
                  <label for="password">密码</label>
                  <input id="password" name="password" type="password" autocomplete="current-password"/>
                </div>
                <button type="submit">登录</button>
              </form>
            </main>
            </body>
            </html>
            """;

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

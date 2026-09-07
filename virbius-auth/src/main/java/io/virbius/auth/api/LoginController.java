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
        String err = error == null ? "" : "<p>" + escape(error) + "</p>";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Virbius login</title></head><body>"
                + "<h1>Virbius 运营登录</h1>"
                + err
                + "<form method=\"post\" action=\"/login\">"
                + "<input type=\"hidden\" name=\"return_uri\" value=\""
                + escape(returnUri)
                + "\"/>"
                + "<input type=\"hidden\" name=\"state\" value=\""
                + escape(state)
                + "\"/>"
                + "<label>用户名 <input name=\"username\" autocomplete=\"username\"/></label>"
                + "<label>密码 <input type=\"password\" name=\"password\" autocomplete=\"current-password\"/></label>"
                + "<button type=\"submit\">登录</button></form></body></html>";
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
}

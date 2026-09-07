package io.virbius.auth.api;

import io.virbius.auth.security.JwtIssuer;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

    private final JwtIssuer issuer;

    public JwksController(JwtIssuer issuer) {
        this.issuer = issuer;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return issuer.jwks();
    }
}

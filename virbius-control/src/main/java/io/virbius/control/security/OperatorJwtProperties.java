package io.virbius.control.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.security.operator-jwt")
public class OperatorJwtProperties {

    private boolean enabled = false;
    private String jwksUrl = "http://127.0.0.1:8082/.well-known/jwks.json";
    private String issuer = "http://127.0.0.1:8082";
    private String audience = "virbius-control";
    private String loginUrl = "http://127.0.0.1:8082/login";
    private String tokenUrl = "http://127.0.0.1:8082/oauth/token";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }
}

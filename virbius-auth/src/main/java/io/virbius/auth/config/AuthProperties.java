package io.virbius.auth.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.auth")
public class AuthProperties {

    private String issuer = "http://127.0.0.1:8082";
    private String audience = "virbius-control";
    private List<String> returnUris = new ArrayList<>();
    private final Bootstrap bootstrap = new Bootstrap();

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

    public List<String> getReturnUris() {
        return returnUris;
    }

    public void setReturnUris(List<String> returnUris) {
        this.returnUris = returnUris;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public boolean isReturnUriAllowed(String returnUri) {
        return returnUri != null && returnUris.contains(returnUri);
    }

    public static class Bootstrap {
        private String username = "";
        private String password = "";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}

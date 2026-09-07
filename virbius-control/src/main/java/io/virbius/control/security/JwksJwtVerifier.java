package io.virbius.control.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "virbius.security.operator-jwt", name = "enabled", havingValue = "true")
public class JwksJwtVerifier {

    private final DefaultJWTProcessor<SecurityContext> processor;

    public JwksJwtVerifier(OperatorJwtProperties properties) {
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        try {
            JWKSource<SecurityContext> source =
                    JWKSourceBuilder.create(URI.create(properties.getJwksUrl()).toURL()).build();
            p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, source));
        } catch (Exception e) {
            throw new IllegalStateException("invalid JWKS URL: " + properties.getJwksUrl(), e);
        }
        JWTClaimsSet exact = new JWTClaimsSet.Builder()
                .issuer(properties.getIssuer())
                .audience(properties.getAudience())
                .build();
        DefaultJWTClaimsVerifier<SecurityContext> claims =
                new DefaultJWTClaimsVerifier<>(exact, Set.of("sub", "exp", "role", "tenant_id"));
        claims.setMaxClockSkew(60);
        p.setJWTClaimsSetVerifier(claims);
        this.processor = p;
    }

    public Optional<ApiKeyPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            JWTClaimsSet claims = processor.process(token, null);
            String role = claims.getStringClaim("role");
            String tenantId = claims.getStringClaim("tenant_id");
            String label = claims.getStringClaim("preferred_username");
            if (role == null || tenantId == null) {
                return Optional.empty();
            }
            return Optional.of(new ApiKeyPrincipal(
                    claims.getSubject(), tenantId, ApiRole.parse(role), label == null ? "" : label));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

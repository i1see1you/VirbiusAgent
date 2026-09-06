package io.virbius.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.virbius.auth.config.AuthProperties;
import io.virbius.auth.domain.OperatorUser;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LocalJwtVerifier {

    private static final long SKEW_SECONDS = 60;

    private final JwtIssuer issuer;
    private final AuthProperties properties;

    public LocalJwtVerifier(JwtIssuer issuer, AuthProperties properties) {
        this.issuer = issuer;
        this.properties = properties;
    }

    public Optional<OperatorUser> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new ECDSAVerifier(issuer.publicKey()))) {
                return Optional.empty();
            }
            var claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || Instant.now().isAfter(exp.toInstant().plusSeconds(SKEW_SECONDS))) {
                return Optional.empty();
            }
            if (!properties.getIssuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(properties.getAudience())) {
                return Optional.empty();
            }
            String role = claims.getStringClaim("role");
            String tenantId = claims.getStringClaim("tenant_id");
            String username = claims.getStringClaim("preferred_username");
            if (role == null || tenantId == null) {
                return Optional.empty();
            }
            return Optional.of(new OperatorUser(
                    claims.getSubject(), username, null, role, tenantId, OperatorUser.STATUS_ACTIVE, Instant.now()));
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }
}

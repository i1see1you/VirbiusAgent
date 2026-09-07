package io.virbius.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.virbius.auth.config.AuthProperties;
import io.virbius.auth.domain.OperatorUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtIssuer {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(60);
    private static final Logger log = LoggerFactory.getLogger(JwtIssuer.class);

    private final AuthProperties properties;
    private final ECKey key;

    public JwtIssuer(AuthProperties properties, @Value("${VIRBIUS_AUTH_DATA_DIR:./data}") String dataDir) {
        this.properties = properties;
        this.key = loadOrCreate(dataDir);
    }

    public String issue(OperatorUser user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.userId())
                .issuer(properties.getIssuer())
                .audience(properties.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_TTL)))
                .claim("role", user.role())
                .claim("tenant_id", user.tenantId())
                .claim("preferred_username", user.username())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign JWT", e);
        }
    }

    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    public ECKey publicKey() {
        return key.toPublicJWK();
    }

    private static ECKey loadOrCreate(String dataDir) {
        try {
            if (dataDir == null || dataDir.isBlank()) {
                return new ECKeyGenerator(Curve.P_256).keyID("auth-es256").generate();
            }
            Path file = Path.of(dataDir).resolve("jwt-es256.jwk.json");
            if (Files.isRegularFile(file)) {
                log.info("loaded JWT signing key from {}", file.toAbsolutePath());
                return ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
            Files.createDirectories(file.getParent());
            ECKey generated = new ECKeyGenerator(Curve.P_256).keyID("auth-es256").generate();
            Files.writeString(file, generated.toJSONString(), StandardCharsets.UTF_8);
            log.info("generated JWT signing key at {}", file.toAbsolutePath());
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load or create ES256 key", e);
        }
    }
}

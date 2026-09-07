package io.virbius.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.virbius.auth.config.AuthProperties;
import io.virbius.auth.domain.AuthCode;
import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.security.JwtIssuer;
import io.virbius.auth.security.PasswordHasher;
import io.virbius.auth.security.Sha256;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoginAndTokenTest {

    private static final String RETURN = "http://127.0.0.1:8080/ui/callback";

    @Test
    void rejectsUnknownReturnUri() {
        Fixture f = Fixture.create();
        assertFalse(f.login.isReturnUriAllowed("https://evil.example/cb"));
        assertTrue(f.login.login("admin", "secret", "https://evil.example/cb").isEmpty());
    }

    @Test
    void wrongPasswordAndUnknownUserShareFailure() {
        Fixture f = Fixture.create();
        assertTrue(f.login.login("admin", "nope", RETURN).isEmpty());
        assertTrue(f.login.login("nobody", "secret", RETURN).isEmpty());
    }

    @Test
    void disabledUserCannotLogin() {
        Fixture f = Fixture.create();
        f.users.disable(f.users.findByUsername("admin").orElseThrow().userId());
        assertTrue(f.login.login("admin", "secret", RETURN).isEmpty());
    }

    @Test
    void codeExchangesOnceForJwtMatchingJwks() throws Exception {
        Fixture f = Fixture.create();
        String code = f.login.login("admin", "secret", RETURN).orElseThrow();
        Map<String, Object> token = f.tokens.exchange("authorization_code", code).orElseThrow();
        String jwt = (String) token.get("access_token");

        SignedJWT parsed = SignedJWT.parse(jwt);
        JWKSet jwks = JWKSet.parse(f.issuer.jwks());
        JWK key = jwks.getKeyByKeyId(parsed.getHeader().getKeyID());
        assertTrue(parsed.verify(new ECDSAVerifier(key.toECKey())));
        assertEquals("platform_admin", parsed.getJWTClaimsSet().getStringClaim("role"));
        assertEquals("*", parsed.getJWTClaimsSet().getStringClaim("tenant_id"));
        assertEquals("admin", parsed.getJWTClaimsSet().getStringClaim("preferred_username"));
        assertEquals("virbius-control", parsed.getJWTClaimsSet().getAudience().get(0));

        assertTrue(f.tokens.exchange("authorization_code", code).isEmpty());
        assertTrue(f.tokens.exchange("authorization_code", "unknown").isEmpty());
    }

    @Test
    void expiredCodeIsRejected() {
        Fixture f = Fixture.create();
        String raw = "expired-code";
        String userId = f.users.findByUsername("admin").orElseThrow().userId();
        f.codes.insert(new AuthCode(Sha256.hex(raw), userId, RETURN, Instant.now().minusSeconds(1), null));
        assertTrue(f.tokens.exchange("authorization_code", raw).isEmpty());
    }

    @Test
    void unknownKidDoesNotVerify() throws Exception {
        Fixture f = Fixture.create();
        String code = f.login.login("admin", "secret", RETURN).orElseThrow();
        String jwt = (String) f.tokens.exchange("authorization_code", code).orElseThrow().get("access_token");
        SignedJWT parsed = SignedJWT.parse(jwt);
        JWKSet empty = new JWKSet(List.of());
        assertTrue(empty.getKeyByKeyId(parsed.getHeader().getKeyID()) == null);
    }

    @Test
    void signingKeySurvivesRestartFromDataDir() throws Exception {
        AuthProperties props = new AuthProperties();
        Path dir = Files.createTempDirectory("virbius-jwt");
        JwtIssuer a = new JwtIssuer(props, dir.toString());
        JwtIssuer b = new JwtIssuer(props, dir.toString());
        assertEquals(a.publicKey().toJSONString(), b.publicKey().toJSONString());
    }

    @Test
    void createUserReturnsPublicFieldsOnlyAndDisableStopsLogin() {
        Fixture f = Fixture.create();
        UserAdminService admin = new UserAdminService(f.users, new PasswordHasher());
        OperatorUser created = admin.create("ops", "pw", "tenant_admin", "acme");
        assertEquals(null, created.passwordHash());
        assertFalse(f.users.findByUsername("ops").orElseThrow().passwordHash().contains("pw"));

        f.users.disable(created.userId());
        assertTrue(f.login.login("ops", "pw", RETURN).isEmpty());
    }

    private record Fixture(
            InMemoryOperatorUserRepository users,
            InMemoryAuthCodeRepository codes,
            LoginService login,
            TokenService tokens,
            JwtIssuer issuer) {

        static Fixture create() {
            InMemoryOperatorUserRepository users = new InMemoryOperatorUserRepository();
            InMemoryAuthCodeRepository codes = new InMemoryAuthCodeRepository();
            AuthProperties props = new AuthProperties();
            props.setReturnUris(List.of(RETURN));
            props.getBootstrap().setUsername("admin");
            props.getBootstrap().setPassword("secret");
            PasswordHasher hasher = new PasswordHasher();
            new BootstrapService(users, hasher, props).bootstrapIfEmpty();
            JwtIssuer issuer = new JwtIssuer(props, null);
            return new Fixture(
                    users,
                    codes,
                    new LoginService(users, codes, hasher, props),
                    new TokenService(codes, users, issuer),
                    issuer);
        }
    }
}

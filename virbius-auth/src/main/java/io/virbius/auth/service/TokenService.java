package io.virbius.auth.service;

import io.virbius.auth.domain.AuthCode;
import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.repository.AuthCodeRepository;
import io.virbius.auth.repository.OperatorUserRepository;
import io.virbius.auth.security.JwtIssuer;
import io.virbius.auth.security.Sha256;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final AuthCodeRepository codes;
    private final OperatorUserRepository users;
    private final JwtIssuer jwtIssuer;

    public TokenService(AuthCodeRepository codes, OperatorUserRepository users, JwtIssuer jwtIssuer) {
        this.codes = codes;
        this.users = users;
        this.jwtIssuer = jwtIssuer;
    }

    public Optional<Map<String, Object>> exchange(String grantType, String code) {
        if (!"authorization_code".equals(grantType) || code == null || code.isBlank()) {
            return Optional.empty();
        }
        String hash = Sha256.hex(code);
        Optional<AuthCode> row = codes.findByHash(hash);
        Instant now = Instant.now();
        if (row.isEmpty() || !row.get().isUsable(now)) {
            return Optional.empty();
        }
        Optional<OperatorUser> user = users.findById(row.get().userId());
        if (user.isEmpty() || !user.get().isActive()) {
            return Optional.empty();
        }
        codes.markConsumed(hash, now);
        String jwt = jwtIssuer.issue(user.get());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", jwt);
        body.put("token_type", "Bearer");
        body.put("expires_in", JwtIssuer.ACCESS_TTL.toSeconds());
        return Optional.of(body);
    }
}

package io.virbius.auth.service;

import io.virbius.auth.config.AuthProperties;
import io.virbius.auth.domain.AuthCode;
import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.repository.AuthCodeRepository;
import io.virbius.auth.repository.OperatorUserRepository;
import io.virbius.auth.security.PasswordHasher;
import io.virbius.auth.security.Sha256;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    public static final Duration CODE_TTL = Duration.ofMinutes(2);
    public static final String GENERIC_FAILURE = "invalid credentials";

    private final OperatorUserRepository users;
    private final AuthCodeRepository codes;
    private final PasswordHasher hasher;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public LoginService(
            OperatorUserRepository users,
            AuthCodeRepository codes,
            PasswordHasher hasher,
            AuthProperties properties) {
        this.users = users;
        this.codes = codes;
        this.hasher = hasher;
        this.properties = properties;
    }

    public boolean isReturnUriAllowed(String returnUri) {
        return properties.isReturnUriAllowed(returnUri);
    }

    public Optional<String> login(String username, String password, String returnUri) {
        if (!isReturnUriAllowed(returnUri)) {
            return Optional.empty();
        }
        Optional<OperatorUser> found = users.findByUsername(username == null ? "" : username.trim());
        if (found.isEmpty() || !found.get().isActive() || !hasher.matches(password, found.get().passwordHash())) {
            return Optional.empty();
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now();
        codes.insert(new AuthCode(Sha256.hex(code), found.get().userId(), returnUri, now.plus(CODE_TTL), null));
        return Optional.of(code);
    }
}

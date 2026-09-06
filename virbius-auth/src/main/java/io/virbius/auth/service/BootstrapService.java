package io.virbius.auth.service;

import io.virbius.auth.config.AuthProperties;
import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.repository.OperatorUserRepository;
import io.virbius.auth.security.PasswordHasher;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    private final OperatorUserRepository users;
    private final PasswordHasher hasher;
    private final AuthProperties properties;

    public BootstrapService(OperatorUserRepository users, PasswordHasher hasher, AuthProperties properties) {
        this.users = users;
        this.hasher = hasher;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapIfEmpty();
    }

    public boolean bootstrapIfEmpty() {
        if (users.count() > 0) {
            return false;
        }
        String username = properties.getBootstrap().getUsername();
        String password = properties.getBootstrap().getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("operator store empty and bootstrap credentials unset");
            return false;
        }
        users.insert(new OperatorUser(
                UUID.randomUUID().toString(),
                username.trim(),
                hasher.hash(password),
                "platform_admin",
                OperatorUser.PLATFORM_TENANT,
                OperatorUser.STATUS_ACTIVE,
                Instant.now()));
        log.info("bootstrapped platform_admin {}", username.trim());
        return true;
    }
}

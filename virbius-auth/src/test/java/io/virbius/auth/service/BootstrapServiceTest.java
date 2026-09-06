package io.virbius.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.auth.config.AuthProperties;
import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.security.PasswordHasher;
import org.junit.jupiter.api.Test;

class BootstrapServiceTest {

    @Test
    void emptyStoreCreatesOnePlatformAdmin() {
        InMemoryOperatorUserRepository users = new InMemoryOperatorUserRepository();
        AuthProperties props = props("admin", "secret");
        BootstrapService svc = new BootstrapService(users, new PasswordHasher(), props);

        assertTrue(svc.bootstrapIfEmpty());
        assertEquals(1, users.count());
        OperatorUser user = users.findByUsername("admin").orElseThrow();
        assertEquals("platform_admin", user.role());
        assertEquals("*", user.tenantId());
        assertTrue(new PasswordHasher().matches("secret", user.passwordHash()));
        assertFalse(user.passwordHash().contains("secret"));
    }

    @Test
    void secondBootstrapDoesNotInsert() {
        InMemoryOperatorUserRepository users = new InMemoryOperatorUserRepository();
        AuthProperties props = props("admin", "secret");
        BootstrapService svc = new BootstrapService(users, new PasswordHasher(), props);

        assertTrue(svc.bootstrapIfEmpty());
        assertFalse(svc.bootstrapIfEmpty());
        assertEquals(1, users.count());
    }

    private static AuthProperties props(String user, String pass) {
        AuthProperties p = new AuthProperties();
        p.getBootstrap().setUsername(user);
        p.getBootstrap().setPassword(pass);
        return p;
    }
}

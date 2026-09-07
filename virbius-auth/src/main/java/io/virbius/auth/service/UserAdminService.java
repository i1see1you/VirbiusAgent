package io.virbius.auth.service;

import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.repository.OperatorUserRepository;
import io.virbius.auth.security.PasswordHasher;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserAdminService {

    private static final Set<String> ROLES = Set.of("tenant_viewer", "tenant_admin", "platform_admin");

    private final OperatorUserRepository users;
    private final PasswordHasher hasher;

    public UserAdminService(OperatorUserRepository users, PasswordHasher hasher) {
        this.users = users;
        this.hasher = hasher;
    }

    public List<OperatorUser> list() {
        return users.listAll().stream().map(OperatorUser::withoutSecret).toList();
    }

    public OperatorUser create(String username, String password, String role, String tenantId) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("username and password required");
        }
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("invalid role");
        }
        String tenant = tenantId == null ? "" : tenantId.trim();
        if ("platform_admin".equals(role)) {
            tenant = OperatorUser.PLATFORM_TENANT;
        } else if (tenant.isBlank() || OperatorUser.PLATFORM_TENANT.equals(tenant)) {
            throw new IllegalArgumentException("tenant_id required");
        }
        if (users.findByUsername(username.trim()).isPresent()) {
            throw new IllegalArgumentException("username taken");
        }
        OperatorUser user = new OperatorUser(
                UUID.randomUUID().toString(),
                username.trim(),
                hasher.hash(password),
                role,
                tenant,
                OperatorUser.STATUS_ACTIVE,
                Instant.now());
        users.insert(user);
        return user.withoutSecret();
    }

    public void disable(String userId) {
        if (users.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("user not found");
        }
        users.disable(userId);
    }
}

package io.virbius.auth.service;

import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.repository.OperatorUserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InMemoryOperatorUserRepository implements OperatorUserRepository {

    private final Map<String, OperatorUser> byId = new LinkedHashMap<>();

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public Optional<OperatorUser> findByUsername(String username) {
        return byId.values().stream().filter(u -> u.username().equals(username)).findFirst();
    }

    @Override
    public Optional<OperatorUser> findById(String userId) {
        return Optional.ofNullable(byId.get(userId));
    }

    @Override
    public List<OperatorUser> listAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void insert(OperatorUser user) {
        byId.put(user.userId(), user);
    }

    @Override
    public void disable(String userId) {
        OperatorUser u = byId.get(userId);
        if (u != null) {
            byId.put(
                    userId,
                    new OperatorUser(
                            u.userId(),
                            u.username(),
                            u.passwordHash(),
                            u.role(),
                            u.tenantId(),
                            OperatorUser.STATUS_DISABLED,
                            u.createdAt()));
        }
    }
}

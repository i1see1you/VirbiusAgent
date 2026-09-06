package io.virbius.auth.service;

import io.virbius.auth.domain.AuthCode;
import io.virbius.auth.repository.AuthCodeRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class InMemoryAuthCodeRepository implements AuthCodeRepository {

    private final Map<String, AuthCode> byHash = new LinkedHashMap<>();

    @Override
    public void insert(AuthCode code) {
        byHash.put(code.codeHash(), code);
    }

    @Override
    public Optional<AuthCode> findByHash(String codeHash) {
        return Optional.ofNullable(byHash.get(codeHash));
    }

    @Override
    public void markConsumed(String codeHash, Instant consumedAt) {
        AuthCode c = byHash.get(codeHash);
        if (c != null) {
            byHash.put(codeHash, new AuthCode(c.codeHash(), c.userId(), c.returnUri(), c.expiresAt(), consumedAt));
        }
    }
}

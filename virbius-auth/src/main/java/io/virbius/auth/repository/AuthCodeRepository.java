package io.virbius.auth.repository;

import io.virbius.auth.domain.AuthCode;
import java.time.Instant;
import java.util.Optional;

public interface AuthCodeRepository {

    void insert(AuthCode code);

    Optional<AuthCode> findByHash(String codeHash);

    void markConsumed(String codeHash, Instant consumedAt);
}

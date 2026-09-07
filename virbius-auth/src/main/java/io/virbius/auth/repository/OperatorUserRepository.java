package io.virbius.auth.repository;

import io.virbius.auth.domain.OperatorUser;
import java.util.List;
import java.util.Optional;

public interface OperatorUserRepository {

    long count();

    Optional<OperatorUser> findByUsername(String username);

    Optional<OperatorUser> findById(String userId);

    List<OperatorUser> listAll();

    void insert(OperatorUser user);

    void disable(String userId);
}

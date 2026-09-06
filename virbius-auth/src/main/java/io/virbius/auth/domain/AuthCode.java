package io.virbius.auth.domain;

import java.time.Instant;

public record AuthCode(String codeHash, String userId, String returnUri, Instant expiresAt, Instant consumedAt) {

    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }
}

package io.virbius.control.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class TimeHelper {

    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeHelper() {}

    static String nowIso() {
        return Instant.now().toString();
    }

    static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(s.trim(), SQL_TIMESTAMP).toInstant(ZoneOffset.UTC);
        }
    }
}
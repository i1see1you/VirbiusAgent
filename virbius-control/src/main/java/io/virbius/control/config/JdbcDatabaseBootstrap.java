package io.virbius.control.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** SQLite file-db path helper; unnecessary for other JDBC dialects. */
public final class JdbcDatabaseBootstrap {

    private JdbcDatabaseBootstrap() {}

    static void ensureSqliteParentDir(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return;
        }
        try {
            String path = url.substring("jdbc:sqlite:".length());
            if (path.startsWith("file:")) {
                path = path.substring("file:".length());
            }
            if (path.isBlank() || ":memory:".equals(path)) {
                return;
            }
            Path file = Path.of(path).toAbsolutePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (Exception ignored) {
            // ignore directories that cannot be created
        }
    }
}

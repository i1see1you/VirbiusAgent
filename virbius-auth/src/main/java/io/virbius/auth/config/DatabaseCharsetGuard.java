package io.virbius.auth.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fails fast when the shared MySQL/MariaDB schema is not utf8mb4, before Flyway
 * creates any table. Auth shares the database with control, so it enforces the
 * same rule early in the startup order.
 */
@Configuration
public class DatabaseCharsetGuard {

    private static final String REQUIRED_CHARSET = "utf8mb4";

    @Bean
    public FlywayMigrationStrategy utf8mb4ThenMigrate(DataSource dataSource) {
        return flyway -> {
            requireUtf8mb4(dataSource);
            flyway.migrate();
        };
    }

    static void requireUtf8mb4(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
            if (!normalized.contains("mysql") && !normalized.contains("mariadb")) {
                return;
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT @@character_set_database")) {
                if (!rs.next()) {
                    throw new IllegalStateException("could not read @@character_set_database");
                }
                String charset = rs.getString(1);
                if (!REQUIRED_CHARSET.equalsIgnoreCase(charset)) {
                    throw new IllegalStateException(
                            "database character set is '" + charset + "' but " + REQUIRED_CHARSET
                                    + " is required; create the schema first with: "
                                    + "CREATE DATABASE <db> CHARACTER SET utf8mb4 "
                                    + "COLLATE utf8mb4_unicode_ci;");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to verify the database character set", e);
        }
    }
}

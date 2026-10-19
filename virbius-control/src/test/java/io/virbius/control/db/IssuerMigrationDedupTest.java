package io.virbius.control.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * T4: the V19 de-duplication statement keeps the earliest-created active issuer and
 * retires the rest. Executed against an in-memory SQLite DB (the migration's UPDATE
 * is dialect-portable; the ALTER/CREATE INDEX are MySQL/SQLite specific and covered
 * by SchemaIndexParityTest).
 */
class IssuerMigrationDedupTest {

    @Test
    void v19DedupKeepsEarliestActiveIssuer() throws Exception {
        String file = Files.readString(Path.of(
                "src/main/resources/db/migration/V19__evaluate_token_issuer_unique.sql"));
        // Strip comment lines first: the header comments contain ';', which would
        // otherwise truncate the statement split mid-comment.
        String noComments = Arrays.stream(file.split("\n"))
                .filter(l -> !l.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        String update = Arrays.stream(noComments.split(";"))
                .map(String::trim)
                .filter(s -> s.toUpperCase().contains("UPDATE TB_EVALUATE_TOKEN_ISSUERS"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V19 UPDATE statement not found"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE tb_evaluate_token_issuers ("
                    + "kid VARCHAR(64) PRIMARY KEY, key_type VARCHAR(16) NOT NULL DEFAULT 'control',"
                    + "public_key_pem TEXT NOT NULL, encrypted_private_key TEXT NOT NULL DEFAULT '',"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'active', description VARCHAR(255) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, retired_at TIMESTAMP,"
                    + "CHECK (status IN ('active','retired')))");
            st.execute("INSERT INTO tb_evaluate_token_issuers (kid, public_key_pem, status, created_at)"
                    + " VALUES ('kid-new', 'pem', 'active', '2026-02-01 00:00:00')");
            st.execute("INSERT INTO tb_evaluate_token_issuers (kid, public_key_pem, status, created_at)"
                    + " VALUES ('kid-old', 'pem', 'active', '2026-01-01 00:00:00')");
            st.execute("INSERT INTO tb_evaluate_token_issuers (kid, public_key_pem, status, created_at)"
                    + " VALUES ('kid-retired', 'pem', 'retired', '2025-12-01 00:00:00')");

            st.execute(update);

            try (ResultSet rs = st.executeQuery(
                    "SELECT kid FROM tb_evaluate_token_issuers WHERE status = 'active'")) {
                String only = null;
                int n = 0;
                while (rs.next()) {
                    only = rs.getString(1);
                    n++;
                }
                assertEquals(1, n, "exactly one active issuer must remain");
                assertEquals("kid-old", only, "earliest-created active issuer wins");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT " + Arrays.stream(new String[]{"kid-new", "kid-retired"})
                            .map(k -> "SUM(kid = '" + k + "')").collect(Collectors.joining(" + "))
                            + " FROM tb_evaluate_token_issuers WHERE status = 'retired'")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "both losers retired");
            }
        }
    }
}

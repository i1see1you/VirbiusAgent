package io.virbius.control.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T12: index/constraint parity that {@link SchemaDialectParityTest} (columns only)
 * cannot see. Guards the enforcement mechanisms the multi-replica fixes depend on:
 * single-active issuer, audit-archive uniqueness (dev parity), trace-archive
 * uniqueness (inherited on MySQL via CREATE TABLE ... LIKE from the V1 baseline;
 * the SQLite dev schema declares it inline).
 */
class SchemaIndexParityTest {

    private static final Path SCHEMA = Path.of("src/main/resources/db/schema.sql");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void singleActiveIssuerIndexDeclaredInBothDialects() throws Exception {
        String dev = Files.readString(SCHEMA);
        String v19 = Files.readString(MIGRATIONS.resolve("V19__evaluate_token_issuer_unique.sql"));
        assertTrue(dev.contains("uk_evaluate_token_issuers_active"),
                "dev schema.sql must declare the single-active-issuer index");
        assertTrue(v19.contains("uk_evaluate_token_issuers_active"),
                "V19 must create the single-active-issuer index");
        assertTrue(dev.contains("active_guard") && v19.contains("active_guard"),
                "active_guard generated column must exist in both dialects");
    }

    @Test
    void auditArchiveUniquenessDeclaredInDev() throws Exception {
        String dev = Files.readString(SCHEMA);
        assertTrue(dev.contains("uq_tb_audit_events_archive_event_id"),
                "dev schema.sql must declare the audit-archive unique index (MySQL gets it via LIKE)");
    }

    @Test
    void traceArchiveUniquenessDeclaredInDev() throws Exception {
        String dev = Files.readString(SCHEMA);
        assertTrue(dev.contains("UNIQUE (trace_id, step_id)"),
                "dev schema.sql must declare the trace-archive unique key "
                        + "(MySQL gets it via CREATE TABLE ... LIKE tb_agent_trace, V1 baseline:489)");
    }
}

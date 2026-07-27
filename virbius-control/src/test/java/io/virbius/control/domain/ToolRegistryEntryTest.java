package io.virbius.control.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToolRegistryEntryTest {

    @Test
    void approvalModeDefaultsToStrict() {
        ToolRegistryEntry entry = ToolRegistryEntry.create(
                "default", "read_file", "low", "none", 30000, false, null, null, null);
        assertEquals("strict", entry.approvalMode());
    }

    @Test
    void approvalModeAcceptsStrictAndLax() {
        ToolRegistryEntry strict = ToolRegistryEntry.create(
                "default", "read_file", "low", "none", 30000, false, null, null, "strict");
        assertEquals("strict", strict.approvalMode());

        ToolRegistryEntry lax = ToolRegistryEntry.create(
                "default", "query_audit_events", "low", "none", 30000, false, null, null, "lax");
        assertEquals("lax", lax.approvalMode());
    }

    @Test
    void approvalModeNormalizesCase() {
        ToolRegistryEntry entry = ToolRegistryEntry.create(
                "default", "read_file", "low", "none", 30000, false, null, null, "LAX");
        assertEquals("lax", entry.approvalMode());
    }

    @Test
    void approvalModeRejectsInvalidValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolRegistryEntry.create(
                        "default", "read_file", "low", "none", 30000, false, null, null, "weak"));
        assertEquals(true, e.getMessage().contains("invalid approval_mode"));
    }
}

package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CumulativeDimensionTest {

    @Test
    void acceptsBuiltins() {
        assertEquals("user_id", CumulativeDimension.validate("user_id"));
        assertEquals("keyword", CumulativeDimension.validate("keyword"));
    }

    @Test
    void acceptsVarLogical() {
        assertEquals("var:app_id", CumulativeDimension.validate("var:app_id"));
        assertTrue(CumulativeDimension.isVar("var:app_id"));
        assertEquals("app_id", CumulativeDimension.varLogical("var:app_id").orElseThrow());
    }

    @Test
    void rejectsBareVarAndAppId() {
        assertThrows(IllegalArgumentException.class, () -> CumulativeDimension.validate("var"));
        assertThrows(IllegalArgumentException.class, () -> CumulativeDimension.validate("app_id"));
    }
}

package io.virbius.control.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AccessListMetaDimensionTest {

    @Test
    void acceptsVarLogical() {
        assertEquals("var:app_id", AccessListMetaDimension.validate("var:app_id"));
    }

    @Test
    void rejectsPlainVar() {
        assertThrows(IllegalArgumentException.class, () -> AccessListMetaDimension.validate("var"));
    }
}

package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ListStorageKindTest {

    @Test
    void memoryDimensions() {
        assertEquals(ListStorageKind.MEMORY, ListStorageKind.fromDimension("keyword"));
        assertEquals(ListStorageKind.MEMORY, ListStorageKind.fromDimension("ip_cidr"));
    }

    @Test
    void redisDimensions() {
        assertEquals(ListStorageKind.REDIS, ListStorageKind.fromDimension("user_id"));
        assertEquals(ListStorageKind.REDIS, ListStorageKind.fromDimension("device_id"));
        assertEquals(ListStorageKind.REDIS, ListStorageKind.fromDimension("var:app_id"));
    }
}

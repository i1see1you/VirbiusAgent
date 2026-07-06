package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessListServiceMemoryLimitTest {

    @Test
    void countActiveEntriesIgnoresExpired() {
        Instant now = Instant.parse("2026-05-20T12:00:00Z");
        List<AccessListEntry> entries = List.of(
                new AccessListEntry("a", null, null, null),
                new AccessListEntry("b", null, null, now.plusSeconds(3600)),
                new AccessListEntry("c", null, null, now.minusSeconds(1)));
        assertEquals(2, AccessListService.countActiveEntries(entries, now));
    }

    @Test
    void rejectsWhenMemoryListAtCapacity() {
        Instant now = Instant.parse("2026-05-20T12:00:00Z");
        List<AccessListEntry> existing = new ArrayList<>();
        for (int i = 0; i < AccessListService.MEMORY_LIST_MAX_ACTIVE_ENTRIES; i++) {
            existing.add(new AccessListEntry("v" + i, null, null, null));
        }
        AccessListMeta meta = new AccessListMeta("default", "deny_keyword", "keyword", null);
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessListService.ensureMemoryListCapacity(meta, existing, "new", null, now));
    }
}

package io.virbius.control.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LicenseToolsTest {

    @Test
    void emptyListMeansWildcardAndStaysEmptyAfterCsvRoundTrip() {
        assertTrue(LicenseTools.normalize(List.of()).isEmpty());
        assertTrue(LicenseTools.normalize(null).isEmpty());
        assertEquals("", LicenseTools.toCsv(List.of()));
        // "".split(",") in Java returns [""] — the parser must not invent an entry.
        assertTrue(LicenseTools.parseCsv("").isEmpty());
        assertTrue(LicenseTools.parseCsv("   ").isEmpty());
        assertTrue(LicenseTools.parseCsv(null).isEmpty());
    }

    @Test
    void blanksAndDuplicatesAreDroppedAndValuesTrimmed() {
        List<String> normalized = LicenseTools.normalize(Arrays.asList(" read_file ", "", null, "read_file", "exec"));
        assertEquals(List.of("read_file", "exec"), normalized);
        assertEquals("read_file,exec", LicenseTools.toCsv(Arrays.asList(" read_file ", "", "exec", "read_file")));
    }

    @Test
    void csvParsingTrimsAndFiltersEmptyEntries() {
        assertEquals(List.of("a", "b"), LicenseTools.parseCsv("a, ,b,"));
        assertEquals(List.of("a"), LicenseTools.parseCsv("a"));
    }
}

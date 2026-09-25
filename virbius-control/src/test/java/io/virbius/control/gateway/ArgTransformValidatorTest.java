package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArgTransformValidatorTest {

    @Test
    void acceptsRestrictMax() {
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"phase\":\"pre_tool_call\",\"mutations\":[{\"path\":\"$.amount\",\"op\":\"restrict\",\"to\":{\"max\":500},\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void rejectsDuplicatePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"truncate\",\"max_len\":2},{\"path\":\"$.a\",\"op\":\"redact\",\"detector\":\"phone_cn\"}]}"));
        assertTrue(e.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsCapOp() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"cap\",\"value\":1}]}"));
    }

    @Test
    void rejectsScanRestrict() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$..*string\",\"op\":\"restrict\",\"to\":{\"max\":1}}]}"));
    }

    @Test
    void acceptsScanMixedWithRestrict() {
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.amount\",\"op\":\"restrict\",\"to\":{\"max\":10},\"on_violation\":\"clamp\"},{\"path\":\"$..*string\",\"op\":\"redact\",\"detector\":\"email\"}]}"));
    }

    @Test
    void blankIsNull() {
        assertNull(ArgTransformValidator.normalize(null));
        assertNull(ArgTransformValidator.normalize("  "));
    }

    @Test
    void acceptsPrefixClamp() {
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.url\",\"op\":\"restrict\",\"to\":{\"prefixes\":[\"https://\"]},\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void rejectsDeny() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"restrict\",\"to\":[\"x\"],\"on_violation\":\"deny\"}]}"));
    }

    @Test
    void acceptsMatch() {
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.url\",\"op\":\"restrict\",\"to\":{\"match\":\"^https://\"},\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void rejectsBadMatch() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.url\",\"op\":\"restrict\",\"to\":{\"match\":\"(\"},\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void rejectsDomains() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.to\",\"op\":\"restrict\",\"to\":{\"domains\":[\"corp.com\"]},\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void memoryWriteToolRejectsRedact() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArgTransformValidator.normalize(
                        "{\"mutations\":[{\"path\":\"$.content\",\"op\":\"redact\",\"detector\":\"phone_cn\"}]}",
                        "memory_save"));
        assertTrue(e.getMessage().contains("memory-write"));
    }

    @Test
    void memoryWriteToolRejectsTruncate() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.content\",\"op\":\"truncate\",\"max_len\":100}]}",
                "embedding_add"));
    }

    @Test
    void memoryWriteToolAllowsRestrict() {
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.ns\",\"op\":\"restrict\",\"to\":[\"work\"],\"on_violation\":\"clamp\"}]}",
                "memory_save"));
    }

    @Test
    void normalToolAllowsRedact() {
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.note\",\"op\":\"redact\",\"detector\":\"phone_cn\"}]}",
                "send_email"));
    }

    @Test
    void acceptsPathGrammarEquivalence() {
        // Cases the old single-index regex rejected but Rust parse_path accepts —
        // drift anchors: if either grammar tightens again, one of the two suites fails.
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.matrix[0][1]\",\"op\":\"truncate\",\"max_len\":4}]}"));
        assertDoesNotThrow(() -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$[0].a\",\"op\":\"restrict\",\"to\":[\"x\"],\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void rejectsMalformedPaths() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$..deep.a\",\"op\":\"truncate\",\"max_len\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a[x]\",\"op\":\"truncate\",\"max_len\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a.\",\"op\":\"truncate\",\"max_len\":1}]}"));
    }

    @Test
    void rejectsLeadingZeroIndex() {
        // `$.a[01]` aliases `$.a[1]` and would slip past duplicate detection.
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a[01]\",\"op\":\"truncate\",\"max_len\":1}]}"));
    }

    @Test
    void keepsFieldName() {
        String out = ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"truncate\",\"max_len\":1,\"name\":\"金额\"}]}");
        assertTrue(out.contains("\"name\":\"金额\""));
    }

    @Test
    void rejectsValueField() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"truncate\",\"max_len\":1,\"value\":9}]}"));
    }

    @Test
    void rejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"restrict\",\"to\":{\"min\":10,\"max\":5},\"on_violation\":\"clamp\"}]}"));
    }

    @Test
    void rejectsEmptyRange() {
        assertThrows(IllegalArgumentException.class, () -> ArgTransformValidator.normalize(
                "{\"mutations\":[{\"path\":\"$.a\",\"op\":\"restrict\",\"to\":{\"min\":null},\"on_violation\":\"clamp\"}]}"));
    }
}

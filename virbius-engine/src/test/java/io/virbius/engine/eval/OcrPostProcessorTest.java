package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * OcrPostProcessor tests. These also serve as the Java↔Python consistency
 * contract: the expected strings below match scripts/ocr_postprocess.py output.
 */
class OcrPostProcessorTest {

    @Test
    void stripsHtmlTags() {
        assertEquals("服用阿莫西林前请咨询医生",
                OcrPostProcessor.clean("服用阿莫西林前请咨询医生\n<img src=\"bbox_1.jpg\" />"));
    }

    @Test
    void dedupsConsecutiveRepeatedLines() {
        assertEquals("禁止吸烟",
                OcrPostProcessor.clean("禁止吸烟\n\n禁止吸烟\n\n禁止吸烟"));
    }

    @Test
    void stripsMarkdownEmphasis() {
        assertEquals("Hello world",
                OcrPostProcessor.clean("**Hello** *world*"));
    }

    @Test
    void stripsMarkdownImageRef() {
        assertEquals("The weather is fine",
                OcrPostProcessor.clean("The weather is fine ![img](bbox.png)"));
    }

    @Test
    void collapsesWhitespaceAndTrims() {
        assertEquals("line one\nline two",
                OcrPostProcessor.clean("  line one  \n\n  line two  \n"));
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals("", OcrPostProcessor.clean(""));
        assertEquals("", OcrPostProcessor.clean(null));
    }

    @Test
    void keepsListNumbering() {
        assertEquals("1. item a\n2. item b",
                OcrPostProcessor.clean("1. item a\n2. item b"));
    }
}

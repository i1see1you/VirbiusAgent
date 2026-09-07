package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.eval.DocumentTextExtractor.ExtractResult;
import io.virbius.engine.eval.DocumentTextExtractor.Status;
import io.virbius.engine.eval.FileTypeDetector.FileType;
import org.junit.jupiter.api.Test;

class FileTypeDetectorTest {

    @Test
    void detectsPdf() {
        byte[] d = new byte[]{0x25, 0x50, 0x44, 0x46, '-', '1', '.', '7'};
        assertEquals(FileType.PDF, FileTypeDetector.detect(d));
    }

    @Test
    void detectsPng() {
        byte[] d = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals(FileType.IMAGE, FileTypeDetector.detect(d));
    }

    @Test
    void detectsJpeg() {
        byte[] d = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        assertEquals(FileType.IMAGE, FileTypeDetector.detect(d));
    }

    @Test
    void detectsDocx() {
        // PK\x03\x04 + "word/" marker
        byte[] d = new byte[]{0x50, 0x4B, 0x03, 0x04, 'w', 'o', 'r', 'd', '/', 'x'};
        assertEquals(FileType.DOCX, FileTypeDetector.detect(d));
    }

    @Test
    void detectsDoc() {
        byte[] d = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertEquals(FileType.DOC, FileTypeDetector.detect(d));
    }

    @Test
    void unknownForGarbage() {
        byte[] d = new byte[]{0x01, 0x02, 0x03, 0x04};
        assertEquals(FileType.UNKNOWN, FileTypeDetector.detect(d));
    }

    @Test
    void unknownForTooShort() {
        assertEquals(FileType.UNKNOWN, FileTypeDetector.detect(new byte[]{0x25, 0x50}));
    }
}

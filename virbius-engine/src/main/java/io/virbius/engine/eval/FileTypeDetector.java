package io.virbius.engine.eval;

import java.nio.charset.StandardCharsets;

/**
 * Detects file type from magic bytes (never trusts the caller-provided MIME).
 */
public final class FileTypeDetector {

    public enum FileType { IMAGE, PDF, DOCX, DOC, UNKNOWN }

    private FileTypeDetector() {}

    public static FileType detect(byte[] data) {
        if (data == null || data.length < 4) {
            return FileType.UNKNOWN;
        }
        // PDF: "%PDF-"
        if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46) {
            return FileType.PDF;
        }
        // DOCX (OOXML zip): PK\x03\x04 + "word/" marker in archive
        if (data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
            if (contains(data, "word/".getBytes(StandardCharsets.US_ASCII))) {
                return FileType.DOCX;
            }
            return FileType.UNKNOWN;
        }
        // DOC (OLE2): D0 CF 11 E0 A1 B1 1A E1
        if (data[0] == (byte) 0xD0 && data[1] == (byte) 0xCF && data[2] == 0x11 && data[3] == (byte) 0xE0) {
            return FileType.DOC;
        }
        // Images: PNG / JPEG / GIF / BMP
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return FileType.IMAGE;   // PNG
        }
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
            return FileType.IMAGE;   // JPEG
        }
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) {
            return FileType.IMAGE;   // GIF
        }
        if (data[0] == 0x42 && data[1] == 0x4D) {
            return FileType.IMAGE;   // BMP
        }
        return FileType.UNKNOWN;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

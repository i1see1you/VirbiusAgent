package io.virbius.engine.eval;

/**
 * OCR call result. Distinguishes "image contains no text" from "OCR service
 * unavailable" so the caller can route to review instead of a silent allow.
 */
public record OcrResult(String text, Status status) {

    public enum Status { OK, EMPTY, FAILED, BREAKER_OPEN }

    static OcrResult ok(String text) {
        return new OcrResult(text == null ? "" : text, Status.OK);
    }

    static OcrResult empty() {
        return new OcrResult("", Status.EMPTY);
    }

    static OcrResult failed() {
        return new OcrResult("", Status.FAILED);
    }

    static OcrResult breakerOpen() {
        return new OcrResult("", Status.BREAKER_OPEN);
    }
}

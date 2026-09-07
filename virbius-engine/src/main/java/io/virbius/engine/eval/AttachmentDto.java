package io.virbius.engine.eval;

/**
 * Multimodal attachment for /v1/evaluate.
 *
 * <p>Carries a base64-encoded file (image/PDF/Word) extracted from the
 * chat/completions content array by the gateway or caller. The engine's
 * FileGuardService extracts text (OvisOCR2 for images, PDFBox/POI for
 * documents) and feeds it to the existing text detection pipeline.
 *
 * @param mimeType  hint from caller (e.g. "image/png"); the engine re-detects
 *                  via magic bytes and does not trust this field
 * @param dataBase64 the raw file bytes, base64-encoded (data: URI payload)
 * @param name      optional filename for audit logging
 */
public record AttachmentDto(
        String mimeType,
        String dataBase64,
        String name) {}

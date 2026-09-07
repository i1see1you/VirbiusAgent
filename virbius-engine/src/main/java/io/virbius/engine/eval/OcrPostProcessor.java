package io.virbius.engine.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-processes OCR output before feeding the guard model.
 *
 * <p>MUST stay byte-for-byte consistent with the training-side Python
 * {@code scripts/ocr_postprocess.py::post_process_ocr}, so the inference
 * distribution matches the V15 training distribution. Only strips OCR/markdown
 * artifacts — never legitimate text content.
 *
 * <p>Rules (same as Python):
 *  1. strip HTML tags
 *  2. strip markdown image refs / emphasis / headers
 *  3. split lines, trim, drop consecutive duplicates
 */
public final class OcrPostProcessor {

    private OcrPostProcessor() {}

    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw;
        t = t.replaceAll("<[^>]+>", "");                  // HTML tags
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", ""); // markdown images
        t = t.replaceAll("(\\*\\*|__|\\*|`|~~)", "");       // emphasis/inline
        t = t.replaceAll("(?m)^#{1,6}\\s*", "");            // headers

        String[] rawLines = t.split("\n");
        List<String> lines = new ArrayList<>();
        for (String ln : rawLines) {
            String s = ln.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(s)) {
                continue;   // consecutive duplicate
            }
            lines.add(s);
        }
        return String.join("\n", lines);
    }
}

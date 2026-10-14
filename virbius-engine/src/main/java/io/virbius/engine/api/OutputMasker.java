package io.virbius.engine.api;

import java.util.regex.Pattern;

/**
 * B3: conservative output redaction for the {@code redact} disposal action.
 * Masks PII/credential patterns in-place; used only when the action resolver
 * chooses {@code redact} (otherwise content is rejected or omitted).
 */
public final class OutputMasker {

    private OutputMasker() {}

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CN_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern CN_IDCARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern AWS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer|Authorization)\\s+[A-Za-z0-9._-]{16,}");

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        out = PRIVATE_KEY.matcher(out).replaceAll("[REDACTED_PRIVATE_KEY]");
        out = AWS_KEY.matcher(out).replaceAll("[REDACTED_AWS_KEY]");
        out = BEARER.matcher(out).replaceAll("$1 [REDACTED_TOKEN]");
        out = EMAIL.matcher(out).replaceAll("[REDACTED_EMAIL]");
        out = CN_IDCARD.matcher(out).replaceAll("[REDACTED_ID]");
        out = CN_PHONE.matcher(out).replaceAll("[REDACTED_PHONE]");
        out = BANK_CARD.matcher(out).replaceAll("[REDACTED_CARD]");
        return out;
    }

    public static boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return EMAIL.matcher(text).find()
                || CN_PHONE.matcher(text).find()
                || CN_IDCARD.matcher(text).find()
                || BANK_CARD.matcher(text).find()
                || AWS_KEY.matcher(text).find()
                || PRIVATE_KEY.matcher(text).find()
                || BEARER.matcher(text).find();
    }
}

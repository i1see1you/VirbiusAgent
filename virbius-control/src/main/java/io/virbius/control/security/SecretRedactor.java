package io.virbius.control.security;

import java.util.regex.Pattern;

/**
 * Masks secret material before it reaches logs or exception messages.
 *
 * <p>P0 scope: URL-embedded credentials ({@code scheme://user:pass@host}) and
 * Virbius token shapes ({@code vrb_tk_...}, JWT-ish three-part base64url
 * strings). P0.5 reuses this for credential-plane error paths.
 */
public final class SecretRedactor {

    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("(\\w+://)([^/@:\\s]+):([^@\\s/]+)@");
    private static final Pattern URL_EMPTY_USER =
            Pattern.compile("(\\w+://):([^@\\s/]+)@");
    private static final Pattern VRB_TOKEN = Pattern.compile("vrb_tk_[A-Za-z0-9_-]+");
    private static final Pattern JWT_LIKE =
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    private SecretRedactor() {}

    /** Redact known secret shapes; never returns null. */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        String out = URL_CREDENTIALS.matcher(input).replaceAll("$1***:***@");
        out = URL_EMPTY_USER.matcher(out).replaceAll("$1***@");
        out = VRB_TOKEN.matcher(out).replaceAll("vrb_tk_***");
        out = JWT_LIKE.matcher(out).replaceAll("<redacted-jwt>");
        return out;
    }

    /** Safe toString helper: {@code redact(String.valueOf(value))}. */
    public static String redact(Object value) {
        return redact(value == null ? "" : String.valueOf(value));
    }
}

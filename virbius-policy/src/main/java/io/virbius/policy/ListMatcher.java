package io.virbius.policy;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ListMatcher {

    private ListMatcher() {}

    public static boolean match(String dimension, String value, String content, List<String> entries) {
        if (value == null || value.isBlank() || entries == null || entries.isEmpty()) {
            return false;
        }
        if ("keyword".equalsIgnoreCase(dimension)) {
            return keywordHit(content, entries);
        }
        if ("var".equalsIgnoreCase(dimension) || dimension.startsWith("var:")) {
            return entries.stream().anyMatch(e -> varEntryHit(value, e));
        }
        Set<String> set = Set.copyOf(entries);
        return set.contains(value);
    }

    private static boolean keywordHit(String content, List<String> keywords) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (kw == null || kw.isEmpty()) {
                continue;
            }
            if (kw.chars().anyMatch(c -> c > 127)) {
                if (content.contains(kw)) {
                    return true;
                }
            } else if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean varEntryHit(String actual, String entry) {
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq >= entry.length() - 1) {
            return entry.equals(actual);
        }
        String want = entry.substring(eq + 1);
        return actual.equals(want);
    }
}

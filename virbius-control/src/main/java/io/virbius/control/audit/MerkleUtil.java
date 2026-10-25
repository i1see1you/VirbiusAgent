package io.virbius.control.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Merkle tree helpers (A3 daily anchor, A4 inclusion proofs).
 *
 * <p>Convention: leaves are the entry hashes in their natural order; internal
 * nodes are {@code SHA-256(left || right)}; an unpaired node at a level is
 * promoted unchanged to the next level (no duplication).
 */
public final class MerkleUtil {

    private MerkleUtil() {}

    /** Hash a single node value (leaf or promoted). */
    public static String leafHash(String value) {
        return sha256Hex(value);
    }

    /** Combine two child hashes into a parent. */
    public static String combine(String left, String right) {
        return sha256Hex(left + right);
    }

    /**
     * Build all levels of the tree over the ordered leaf values.
     * {@code levels.get(0)} is the leaf level; the last level has one root.
     * Empty input yields an empty list (caller decides the empty root).
     */
    public static List<List<String>> buildLevels(List<String> leaves) {
        List<List<String>> levels = new ArrayList<>();
        if (leaves == null || leaves.isEmpty()) {
            return levels;
        }
        List<String> current = new ArrayList<>(leaves);
        levels.add(current);
        while (current.size() > 1) {
            List<String> next = new ArrayList<>((current.size() + 1) / 2);
            for (int i = 0; i < current.size(); i += 2) {
                if (i + 1 < current.size()) {
                    next.add(combine(current.get(i), current.get(i + 1)));
                } else {
                    next.add(current.get(i)); // promote unpaired node
                }
            }
            levels.add(next);
            current = next;
        }
        return levels;
    }

    public static String root(List<List<String>> levels) {
        if (levels == null || levels.isEmpty()) {
            return "";
        }
        return levels.get(levels.size() - 1).get(0);
    }

    /**
     * Inclusion proof for leaf {@code index}: sibling hash + position
     * ("left"/"right") per level.
     */
    public static List<ProofStep> proof(List<List<String>> levels, int index) {
        List<ProofStep> steps = new ArrayList<>();
        if (levels == null || levels.isEmpty()) {
            return steps;
        }
        int i = index;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<String> cur = levels.get(level);
            int sibling = i % 2 == 0 ? i + 1 : i - 1;
            if (sibling < cur.size()) {
                String sib = cur.get(sibling);
                steps.add(new ProofStep(sib, i % 2 == 0 ? "right" : "left"));
            }
            // No sibling (promoted node): no step at this level.
            i /= 2;
        }
        return steps;
    }

    /**
     * Verify an inclusion proof: start from the leaf hash and fold siblings
     * up to the expected root.
     */
    public static boolean verify(String leaf, List<ProofStep> steps, String expectedRoot) {
        String current = leaf;
        for (ProofStep step : steps) {
            current = "left".equals(step.position())
                    ? combine(step.hash(), current)
                    : combine(current, step.hash());
        }
        return expectedRoot != null && expectedRoot.equals(current);
    }

    /**
     * Canonicalize a timestamp for hashing (dialect-proof): whatever the DB
     * driver returns (java.sql.Timestamp, ISO string, "yyyy-MM-dd HH:mm:ss")
     * is folded to epoch-seconds so recomputation never breaks on format.
     */
    public static String canonicalTs(Object o) {
        if (o == null) {
            return "";
        }
        // java.sql.Timestamp carries no timezone. We always write UTC, so read
        // its wall-clock fields back as UTC — this keeps SQLite (raw ISO string)
        // and MySQL (driver Timestamp) hashes identical regardless of JVM zone.
        if (o instanceof java.sql.Timestamp ts) {
            return String.valueOf(ts.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).getEpochSecond());
        }
        if (o instanceof java.util.Date d) {
            return String.valueOf(d.toInstant().getEpochSecond());
        }
        if (o instanceof Number n) {
            // Raw epoch seconds, or a 13+ digit driver-produced epoch-millis value
            // (sqlite-jdbc writes java.sql.Timestamp as a plain millis number).
            long v = n.longValue();
            return String.valueOf(v >= 100_000_000_000L ? roundMsToSeconds(v) : v);
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return "";
        }
        // MySQL folds TIMESTAMP(0) writes by ROUNDING to the nearest second, not
        // truncating — the hash must reproduce the stored instant exactly.
        try {
            return String.valueOf(roundInstantToSeconds(java.time.Instant.parse(s)));
        } catch (Exception ignored) {
            // fall through
        }
        // Some drivers (e.g. sqlite-jdbc) store java.sql.Timestamp as a raw
        // epoch-millis string; fold it back to epoch-seconds.
        if (s.matches("\\d{13,}")) {
            return String.valueOf(roundMsToSeconds(Long.parseLong(s)));
        }
        for (String pattern : new String[] {"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss"}) {
            try {
                return String.valueOf(roundInstantToSeconds(java.time.LocalDateTime
                        .parse(s.length() > pattern.length() ? s.substring(0, pattern.length()) : s,
                                java.time.format.DateTimeFormatter.ofPattern(pattern))
                        .toInstant(java.time.ZoneOffset.UTC)));
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return s;
    }

    public record ProofStep(String hash, String position) {}

    private static long roundInstantToSeconds(java.time.Instant i) {
        return i.getEpochSecond() + (i.getNano() >= 500_000_000 ? 1L : 0L);
    }

    private static long roundMsToSeconds(long millis) {
        long whole = millis / 1000L;
        return (millis % 1000L) >= 500L ? whole + 1L : whole;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

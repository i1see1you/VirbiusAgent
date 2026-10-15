package io.virbius.auth.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the two hand-maintained schema sources against drift:
 *   dev  -> db/schema.sql        (SQLite, applied via spring.sql.init)
 *   prod -> db/migration/*.sql   (MySQL/MariaDB, applied via Flyway)
 *
 * Comparison is structural (table names + column names); column types/engines
 * legitimately differ per dialect.
 */
class SchemaDialectParityTest {

    private static final Path SCHEMA = Path.of("src/main/resources/db/schema.sql");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern CREATE = Pattern.compile(
            "(?is)^create\\s+table\\s+(if\\s+not\\s+exists\\s+)?([a-z0-9_]+)\\s*\\((.*)\\)\\s*(engine\\s*=.*)?$");
    private static final Pattern CREATE_LIKE = Pattern.compile(
            "(?is)^create\\s+table\\s+(if\\s+not\\s+exists\\s+)?([a-z0-9_]+)\\s+like\\s+([a-z0-9_]+)$");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?is)^alter\\s+table\\s+([a-z0-9_]+)\\s+add\\s+column\\s+([a-z0-9_]+)");
    private static final Pattern CHANGE_COLUMN = Pattern.compile(
            "(?is)^alter\\s+table\\s+([a-z0-9_]+)\\s+change\\s+(column\\s+)?([a-z0-9_]+)\\s+([a-z0-9_]+)");
    private static final Pattern DROP_TABLE = Pattern.compile(
            "(?is)^drop\\s+table\\s+(if\\s+exists\\s+)?([a-z0-9_]+)");
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "PRIMARY", "UNIQUE", "FOREIGN", "CONSTRAINT", "CHECK", "KEY", "INDEX", "FULLTEXT", "SPATIAL");

    @Test
    void devSqliteSchemaMatchesMysqlMigrations() throws Exception {
        Map<String, Set<String>> dev = parse(Files.readString(SCHEMA));
        StringBuilder migrations = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparingInt(
                            f -> Integer.parseInt(f.getFileName().toString()
                                    .replaceAll("^V(\\d+)__.*$", "$1"))))
                    .toList()) {
                migrations.append(Files.readString(p)).append('\n');
            }
        }
        Map<String, Set<String>> prod = parse(migrations.toString());

        assertEquals(dev.keySet(), prod.keySet(),
                "table set differs between db/schema.sql (sqlite) and db/migration (mysql)");
        for (String table : dev.keySet()) {
            assertEquals(dev.get(table), prod.get(table),
                    "column set differs for table " + table);
        }
    }

    static Map<String, Set<String>> parse(String sql) {
        Map<String, Set<String>> tables = new TreeMap<>();
        String clean = sql.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--[^\\n]*", "");
        for (String raw : clean.split(";")) {
            String s = raw.trim().replaceAll("\\s+", " ");
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = CREATE.matcher(s);
            if (m.matches()) {
                tables.computeIfAbsent(lower(m.group(2)), k -> new TreeSet<>())
                        .addAll(topLevelColumns(m.group(3)));
                continue;
            }
            m = CREATE_LIKE.matcher(s);
            if (m.matches()) {
                tables.put(lower(m.group(2)), new TreeSet<>(tables.getOrDefault(lower(m.group(3)), Set.of())));
                continue;
            }
            m = ADD_COLUMN.matcher(s);
            if (m.find()) {
                tables.computeIfAbsent(lower(m.group(1)), k -> new TreeSet<>()).add(lower(m.group(2)));
                continue;
            }
            m = CHANGE_COLUMN.matcher(s);
            if (m.find()) {
                Set<String> cols = tables.computeIfAbsent(lower(m.group(1)), k -> new TreeSet<>());
                cols.remove(lower(m.group(3)));
                cols.add(lower(m.group(4)));
                continue;
            }
            m = DROP_TABLE.matcher(s);
            if (m.find()) {
                tables.remove(lower(m.group(2)));
            }
        }
        return tables;
    }

    private static Set<String> topLevelColumns(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());

        Set<String> cols = new TreeSet<>();
        for (String part : parts) {
            String p = part.trim().replaceAll("\\s+", " ");
            if (p.isEmpty()) {
                continue;
            }
            String token = p.split(" ")[0].replaceAll("[`\"'\\[\\]]", "");
            if (token.isEmpty() || CONSTRAINT_KEYWORDS.contains(token.toUpperCase(Locale.ROOT))) {
                continue;
            }
            cols.add(token.toLowerCase(Locale.ROOT));
        }
        return cols;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

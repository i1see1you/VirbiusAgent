package io.virbius.control.config;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SqlDialectConfig {

    public static final String SQLITE = "sqlite";
    public static final String MYSQL = "mysql";
    public static final String POSTGRESQL = "postgresql";

    private final String dialect;

    public SqlDialectConfig(DataSource dataSource) {
        this.dialect = detectDialect(dataSource);
    }

    public String dialect() {
        return dialect;
    }

    public boolean isSqlite() {
        return SQLITE.equals(dialect);
    }

    public boolean isMysql() {
        return MYSQL.equals(dialect);
    }

    public boolean isPostgresql() {
        return POSTGRESQL.equals(dialect);
    }

    private static String detectDialect(DataSource dataSource) {
        try (var conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String name = meta.getDatabaseProductName().toLowerCase();
            if (name.contains("sqlite")) return SQLITE;
            if (name.contains("mysql")) return MYSQL;
            if (name.contains("postgresql") || name.contains("postgres")) return POSTGRESQL;
            return name;
        } catch (Exception e) {
            return SQLITE;
        }
    }
}

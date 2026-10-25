package io.virbius.control.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DatabaseCharsetGuardTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private final Flyway flyway = mock(Flyway.class);

    private void mysql(String charset) throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MariaDB");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT @@character_set_database")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(charset);
    }

    @Test
    void utf8mb4DatabaseMigrates() throws Exception {
        mysql("utf8mb4");

        new DatabaseCharsetGuard().utf8mb4ThenMigrate(dataSource).migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    void latin1DatabaseFailsBeforeMigrating() throws Exception {
        mysql("latin1");

        assertThrows(IllegalStateException.class,
                () -> new DatabaseCharsetGuard().utf8mb4ThenMigrate(dataSource).migrate(flyway));

        verifyNoInteractions(flyway);
    }

    @Test
    void nonMysqlDatabaseSkipsTheCheck() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("SQLite");

        new DatabaseCharsetGuard().utf8mb4ThenMigrate(dataSource).migrate(flyway);

        verify(flyway).migrate();
        verify(connection, never()).createStatement();
    }
}

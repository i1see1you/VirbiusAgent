package io.virbius.auth.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
public class DataSourceConfiguration {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        JdbcDatabaseBootstrap.ensureSqliteParentDir(properties.determineUrl());
        return properties.initializeDataSourceBuilder().build();
    }
}

package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.dialect.JdbcDialect
import org.springframework.data.jdbc.core.dialect.JdbcHsqlDbDialect

@Configuration
class DatabaseConfig {

    // Spring Data JDBC has no SQLite dialect; HSQLDB's ANSI-leaning dialect
    // is compatible enough for the simple CRUD this app does.
    @Bean
    fun jdbcDialect(): JdbcDialect = JdbcHsqlDbDialect.INSTANCE
}

package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.dialect.JdbcHsqlDbDialect
import org.springframework.data.relational.core.dialect.LockClause
import org.springframework.data.relational.core.sql.LockOptions

@Configuration
class DatabaseConfig {

    // Spring Data JDBC has no SQLite dialect; HSQLDB's ANSI-leaning dialect
    // is compatible enough for the simple CRUD this app does — except that it
    // emits "... FOR UPDATE" lock clauses (used when deleting/updating an
    // aggregate that owns child rows, e.g. Vehicle → vehicle_managers), which
    // SQLite rejects. We neutralise the lock clause to an empty string.
    @Bean
    fun jdbcDialect(): JdbcHsqlDbDialect = object : JdbcHsqlDbDialect() {
        override fun lock(): LockClause = NoLockClause
    }

    private object NoLockClause : LockClause {
        override fun getLock(lockOptions: LockOptions): String = ""
        override fun getClausePosition(): LockClause.Position = LockClause.Position.AFTER_ORDER_BY
    }
}

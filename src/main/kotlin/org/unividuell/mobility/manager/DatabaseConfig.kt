package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.jdbc.core.dialect.JdbcHsqlDbDialect
import org.springframework.data.relational.core.dialect.LockClause
import org.springframework.data.relational.core.sql.LockOptions
import java.time.LocalDate

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

    // Store java.time.LocalDate as an ISO TEXT column. SQLite's dynamic typing
    // hands the value back as a String, so without explicit converters Spring
    // can write a LocalDate but not read it back (same friction as Instant).
    @Bean
    fun jdbcCustomConversions(): JdbcCustomConversions =
        JdbcCustomConversions(listOf(LocalDateToText, TextToLocalDate, BooleanToInt, IntToBoolean))

    @WritingConverter
    private object LocalDateToText : Converter<LocalDate, String> {
        override fun convert(source: LocalDate): String = source.toString() // ISO yyyy-MM-dd
    }

    @ReadingConverter
    private object TextToLocalDate : Converter<String, LocalDate> {
        override fun convert(source: String): LocalDate = LocalDate.parse(source)
    }

    // SQLite has no native boolean: values live in INTEGER columns as 0/1. Spring
    // can write a Boolean but reads it back as an Integer, so map both directions
    // explicitly (these only apply where the mapped property is a Boolean).
    @WritingConverter
    private object BooleanToInt : Converter<Boolean, Int> {
        override fun convert(source: Boolean): Int = if (source) 1 else 0
    }

    @ReadingConverter
    private object IntToBoolean : Converter<Int, Boolean> {
        override fun convert(source: Int): Boolean = source != 0
    }
}

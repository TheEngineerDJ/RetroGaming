package com.retrovault.data.jdbc

import com.retrovault.data.SqlDatabase
import com.retrovault.data.SqlFailure
import com.retrovault.data.SqlRow
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/**
 * SQLite over JDBC.
 *
 * Used by tests and by any desktop host. It is deliberately the *same* schema
 * and the same repositories the device runs: a persistence bug found here is a
 * bug found on Android.
 *
 * Access is serialized on one connection. DATABASE.md section 17 requires
 * writes to be serialized through the application layer, and SQLite's own
 * write lock makes a connection pool a false economy for a local, embedded
 * database of this size.
 */
class JdbcSqlDatabase private constructor(private val connection: Connection) : SqlDatabase {

    private val lock = Any()

    override fun execute(sql: String, arguments: List<Any?>) {
        synchronized(lock) {
            try {
                connection.prepareStatement(sql).use { statement ->
                    statement.bind(arguments)
                    statement.execute()
                }
            } catch (failure: SQLException) {
                throw SqlFailure(failure.message ?: "statement failed", failure)
            }
        }
    }

    override fun <T> query(sql: String, arguments: List<Any?>, map: (SqlRow) -> T): List<T> =
        synchronized(lock) {
            try {
                connection.prepareStatement(sql).use { statement ->
                    statement.bind(arguments)
                    statement.executeQuery().use { results ->
                        val rows = mutableListOf<T>()
                        val row = JdbcSqlRow(results)
                        while (results.next()) rows += map(row)
                        rows
                    }
                }
            } catch (failure: SQLException) {
                throw SqlFailure(failure.message ?: "query failed", failure)
            }
        }

    override fun <T> transaction(body: () -> T): T = synchronized(lock) {
        // Nested calls join the outer transaction rather than starting a second
        // one, so a repository can compose its own writes safely.
        if (!connection.autoCommit) return body()
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = synchronized(lock) { connection.close() }

    private fun java.sql.PreparedStatement.bind(arguments: List<Any?>) {
        arguments.forEachIndexed { index, argument ->
            val position = index + 1
            when (argument) {
                null -> setObject(position, null)
                is String -> setString(position, argument)
                is Long -> setLong(position, argument)
                is Int -> setInt(position, argument)
                is Boolean -> setLong(position, if (argument) 1L else 0L)
                is ByteArray -> setBytes(position, argument)
                else -> setString(position, argument.toString())
            }
        }
    }

    private class JdbcSqlRow(private val results: ResultSet) : SqlRow {
        override fun getStringOrNull(index: Int): String? = results.getString(index + 1)

        override fun getLongOrNull(index: Int): Long? {
            val value = results.getLong(index + 1)
            return if (results.wasNull()) null else value
        }
    }

    companion object {
        /** Opens (and creates if needed) a database file. */
        fun open(path: String): JdbcSqlDatabase = create("jdbc:sqlite:$path")

        /** Opens a private in-memory database, for tests. */
        fun inMemory(): JdbcSqlDatabase = create("jdbc:sqlite::memory:")

        private fun create(url: String): JdbcSqlDatabase {
            val connection = try {
                DriverManager.getConnection(url)
            } catch (failure: SQLException) {
                throw SqlFailure(failure.message ?: "could not open the database", failure)
            }
            connection.createStatement().use { statement ->
                // Foreign keys are off by default in SQLite and are how several
                // integrity rules are enforced (Constitution section 239).
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = NORMAL")
            }
            return JdbcSqlDatabase(connection)
        }
    }
}

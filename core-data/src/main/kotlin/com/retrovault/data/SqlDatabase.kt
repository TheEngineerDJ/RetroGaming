package com.retrovault.data

/** One row of a result set, read positionally. */
interface SqlRow {
    fun getStringOrNull(index: Int): String?

    fun getLongOrNull(index: Int): Long?

    fun getString(index: Int): String =
        requireNotNull(getStringOrNull(index)) { "Column $index was unexpectedly null" }

    fun getLong(index: Int): Long =
        requireNotNull(getLongOrNull(index)) { "Column $index was unexpectedly null" }

    fun getInt(index: Int): Int = getLong(index).toInt()

    fun getBoolean(index: Int): Boolean = getLong(index) != 0L
}

/** Raised when the storage engine rejects a statement. */
class SqlFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The minimum SQLite surface RetroVault needs.
 *
 * Deliberately tiny. The domain must not know table names
 * (ARCHITECTURE.md section 10), and the repositories must not know whether
 * they are talking to JDBC or to `android.database`, so this is the whole
 * contract between them.
 */
interface SqlDatabase {
    /** Runs a statement that returns no rows. */
    fun execute(sql: String, arguments: List<Any?> = emptyList())

    fun <T> query(sql: String, arguments: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>

    /**
     * Runs [body] in a transaction, committing on success and rolling back on
     * any throw (DATABASE.md section 16).
     */
    fun <T> transaction(body: () -> T): T

    fun close()
}

/** Convenience for a single expected row. */
fun <T> SqlDatabase.queryOne(sql: String, arguments: List<Any?> = emptyList(), map: (SqlRow) -> T): T? =
    query(sql, arguments, map).firstOrNull()

package com.retrovault.platform.android

import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import com.retrovault.data.Schema
import com.retrovault.data.SqlDatabase
import com.retrovault.data.SqlFailure
import com.retrovault.data.SqlRow

/**
 * The platform SQLite binding.
 *
 * Runs the same schema and the same repositories as the JVM binding, so
 * persistence behaviour verified in tests is the behaviour on a device
 * (ARCHITECTURE.md section 10). That promise is only worth anything if the two
 * bindings agree about *values* as well as about SQL, which is what the typed
 * binding below exists for.
 */
class AndroidSqlDatabase private constructor(
    private val helper: SQLiteOpenHelper,
) : SqlDatabase {

    private val database: SQLiteDatabase get() = helper.writableDatabase

    override fun execute(sql: String, arguments: List<Any?>) {
        try {
            database.execSQL(sql, arguments.toBindArgs())
        } catch (failure: SQLException) {
            throw SqlFailure(failure.message ?: "statement failed", failure)
        } catch (failure: IllegalArgumentException) {
            // A value SQLite cannot bind. Surfaced as a storage failure like
            // any other rejected statement rather than escaping as an
            // unrelated-looking crash.
            throw SqlFailure(failure.message ?: "statement had an unbindable argument", failure)
        }
    }

    /**
     * Runs a query with **typed** bind arguments.
     *
     * `rawQuery` only accepts `String[]`, which is the wrong contract for this
     * codebase twice over. A null argument makes it throw
     * `IllegalArgumentException` from `bindString` rather than binding SQL
     * NULL, and every number arrives as text, leaving correctness resting on
     * SQLite applying column affinity to each comparison. The JDBC binding
     * binds by type, so string-only binding would mean the two platforms could
     * disagree about a query that tests say is correct.
     *
     * `rawQueryWithFactory` hands the `SQLiteQuery` to the caller before it
     * runs, which is the supported way to bind types the framework's own
     * convenience method cannot. This is the same mechanism `androidx.sqlite`
     * uses for its typed queries.
     */
    override fun <T> query(sql: String, arguments: List<Any?>, map: (SqlRow) -> T): List<T> = try {
        database.rawQueryWithFactory(
            { _, masterQuery, editTable, query ->
                query.bindAll(arguments)
                SQLiteCursor(masterQuery, editTable, query)
            },
            sql,
            EMPTY_SELECTION_ARGS,
            NO_EDIT_TABLE,
        ).use { cursor ->
            val rows = mutableListOf<T>()
            val row = AndroidSqlRow(cursor)
            while (cursor.moveToNext()) rows += map(row)
            rows
        }
    } catch (failure: SQLException) {
        throw SqlFailure(failure.message ?: "query failed", failure)
    } catch (failure: IllegalArgumentException) {
        throw SqlFailure(failure.message ?: "query had an unbindable argument", failure)
    }

    override fun <T> transaction(body: () -> T): T {
        // SQLiteDatabase transactions nest natively, so a repository composing
        // its own writes inside an outer transaction is safe.
        database.beginTransaction()
        return try {
            val result = body()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() = helper.close()

    /**
     * Binds one argument per `?`, by type.
     *
     * Index is 1-based, matching SQLite's parameter numbering.
     */
    private fun SQLiteProgram.bindAll(arguments: List<Any?>) {
        arguments.forEachIndexed { position, argument ->
            val index = position + 1
            when (argument) {
                null -> bindNull(index)
                is String -> bindString(index, argument)
                is Long -> bindLong(index, argument)
                is Int -> bindLong(index, argument.toLong())
                is Short -> bindLong(index, argument.toLong())
                is Byte -> bindLong(index, argument.toLong())
                is Boolean -> bindLong(index, if (argument) 1L else 0L)
                is Double -> bindDouble(index, argument)
                is Float -> bindDouble(index, argument.toDouble())
                is ByteArray -> bindBlob(index, argument)
                else -> bindString(index, argument.toString())
            }
        }
    }

    /**
     * `execSQL` accepts only a narrow set of bind types, so values are mapped
     * explicitly rather than passed through. It binds nulls correctly on its
     * own, so a null is passed straight through.
     */
    private fun List<Any?>.toBindArgs(): Array<Any?> = map { argument ->
        when (argument) {
            null, is String, is Long, is Double, is ByteArray -> argument
            is Int -> argument.toLong()
            is Short -> argument.toLong()
            is Byte -> argument.toLong()
            is Float -> argument.toDouble()
            is Boolean -> if (argument) 1L else 0L
            else -> argument.toString()
        }
    }.toTypedArray()

    private class AndroidSqlRow(private val cursor: Cursor) : SqlRow {
        override fun getStringOrNull(index: Int): String? =
            if (cursor.isNull(index)) null else cursor.getString(index)

        override fun getLongOrNull(index: Int): Long? =
            if (cursor.isNull(index)) null else cursor.getLong(index)
    }

    companion object {
        const val DATABASE_NAME: String = "retrovault.db"

        private val EMPTY_SELECTION_ARGS = emptyArray<String>()

        /**
         * The table a cursor would write back through.
         *
         * A leftover of `Cursor.commitUpdates`, removed from the framework long
         * ago; `SQLiteCursor` stores it and nothing in a read-only cursor reads
         * it. `androidx.sqlite` passes null here, but it is Java and the
         * parameter is annotated non-null, so Kotlin refuses. These cursors
         * never write back, and an empty name says so without the cast that
         * forcing a null through would need.
         */
        private const val NO_EDIT_TABLE = ""

        fun open(context: Context, name: String = DATABASE_NAME): AndroidSqlDatabase {
            val helper = object : SQLiteOpenHelper(context, name, null, Schema.CURRENT_VERSION) {
                override fun onConfigure(db: SQLiteDatabase) {
                    // Enforced constraints rather than convention
                    // (Constitution section 239). This must happen here:
                    // `PRAGMA foreign_keys` is a no-op inside a transaction,
                    // and every migration runs in one.
                    db.setForeignKeyConstraintsEnabled(true)
                }

                // Schema owns migration, not SQLiteOpenHelper, so that both
                // platforms run identical, tested code. The helper's own
                // `user_version` is therefore just a number it maintains for
                // itself - RetroVault's version lives in the `schema_version`
                // table that `Schema` reads - and these three callbacks
                // deliberately do nothing.
                override fun onCreate(db: SQLiteDatabase) = Unit

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

                /**
                 * Without this the default implementation throws, so installing
                 * an older build over a newer one would crash on open before
                 * any code could explain why. Migrations are forward-only
                 * (DATABASE.md section 15), so the older build still refuses to
                 * migrate - it just does so as a readable state rather than as
                 * an uncaught exception at startup.
                 */
                override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
            return AndroidSqlDatabase(helper).also { database -> Schema.migrate(database) }
        }
    }
}

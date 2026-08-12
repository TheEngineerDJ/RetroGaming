package com.retrovault.platform.android

import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.retrovault.data.Schema
import com.retrovault.data.SqlDatabase
import com.retrovault.data.SqlFailure
import com.retrovault.data.SqlRow

/**
 * The platform SQLite binding.
 *
 * Runs the same schema and the same repositories as the JVM binding, so
 * persistence behaviour verified in tests is the behaviour on a device
 * (ARCHITECTURE.md section 10).
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
        }
    }

    override fun <T> query(sql: String, arguments: List<Any?>, map: (SqlRow) -> T): List<T> = try {
        database.rawQuery(sql, arguments.map { it?.toString() }.toTypedArray()).use { cursor ->
            val rows = mutableListOf<T>()
            val row = AndroidSqlRow(cursor)
            while (cursor.moveToNext()) rows += map(row)
            rows
        }
    } catch (failure: SQLException) {
        throw SqlFailure(failure.message ?: "query failed", failure)
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
     * `execSQL` accepts only a narrow set of bind types, so values are mapped
     * explicitly rather than passed through.
     */
    private fun List<Any?>.toBindArgs(): Array<Any?> = map { argument ->
        when (argument) {
            null, is String, is Long, is Double, is ByteArray -> argument
            is Int -> argument.toLong()
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

        fun open(context: Context, name: String = DATABASE_NAME): AndroidSqlDatabase {
            val helper = object : SQLiteOpenHelper(context, name, null, Schema.CURRENT_VERSION) {
                override fun onConfigure(db: SQLiteDatabase) {
                    // Enforced constraints rather than convention
                    // (Constitution section 239).
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCreate(db: SQLiteDatabase) = Unit

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
            return AndroidSqlDatabase(helper).also { database ->
                // Migrations are owned by Schema, not by SQLiteOpenHelper, so
                // that both platforms run identical, tested migration code.
                Schema.migrate(database)
            }
        }
    }
}

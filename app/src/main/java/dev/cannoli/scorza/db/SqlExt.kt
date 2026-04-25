package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

internal inline fun <T> SQLiteConnection.query(sql: String, block: (SQLiteStatement) -> T): T =
    prepare(sql).use(block)

internal fun SQLiteConnection.execute(sql: String, vararg args: Any?) {
    prepare(sql).use { stmt ->
        stmt.bindAll(args)
        stmt.step()
    }
}

internal fun SQLiteConnection.executeReturningId(sql: String, vararg args: Any?): Long {
    prepare(sql).use { stmt ->
        stmt.bindAll(args)
        stmt.step()
    }
    return prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
}

internal fun SQLiteStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { index, value ->
        val pos = index + 1
        when (value) {
            null -> bindNull(pos)
            is Long -> bindLong(pos, value)
            is Int -> bindLong(pos, value.toLong())
            is Boolean -> bindLong(pos, if (value) 1L else 0L)
            is String -> bindText(pos, value)
            is Double -> bindDouble(pos, value)
            is Float -> bindDouble(pos, value.toDouble())
            is ByteArray -> bindBlob(pos, value)
            else -> error("unsupported bind type ${value::class.java.name} for SQL $this")
        }
    }
}

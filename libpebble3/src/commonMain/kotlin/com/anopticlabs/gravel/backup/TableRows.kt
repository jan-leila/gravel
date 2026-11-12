package com.anopticlabs.gravel.backup

import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/** How many rows of a table an import wrote, and how many the current schema would not take. */
data class ImportedRows(val written: Int, val refused: Int)

/**
 * A table's rows as JSON objects keyed by column name, for a backup that outlives the schema: an
 * import writes only the columns the current table still has, so a column added since the backup
 * takes its default and one dropped since is ignored. Rows are upserted on their primary key and
 * never deleted, since a synced table's deletes have to go through its own DAO to reach a watch.
 */
class TableRows(private val database: RoomDatabase) {
    /** Calls [row] for each row of [table]; returns how many, or null when the table does not exist. */
    suspend fun export(table: String, row: (JsonObject) -> Unit): Int? = database.useReaderConnection<Int?> { connection ->
        exportRows(connection.runner(), table, row)
    }

    /**
     * Upserts [rows] into [table] in one transaction; null when the table does not exist. A row the
     * current schema refuses (a column it now requires is missing) is counted and skipped, so one
     * row cannot cost the rest of the table.
     */
    suspend fun import(table: String, rows: Sequence<JsonObject>): ImportedRows? {
        val result: ImportedRows? = database.useWriterConnection<ImportedRows?> { connection ->
            connection.immediateTransaction<ImportedRows?> { connection.runner().let { importRows(it, table, rows) } }
        }
        // Observers of the table (DAO flows) re-query on the tracker's refresh.
        database.invalidationTracker.refreshAsync()
        return result
    }
}

/** Runs one statement; the seam between Room's connections and a bare SQLite one in tests. */
internal interface StatementRunner {
    suspend fun <R> prepared(sql: String, block: (SQLiteStatement) -> R): R
}

private fun Transactor.runner() = object : StatementRunner {
    override suspend fun <R> prepared(sql: String, block: (SQLiteStatement) -> R): R = usePrepared(sql, block)
}

internal suspend fun exportRows(runner: StatementRunner, table: String, row: (JsonObject) -> Unit): Int? {
    if (table !in tables(runner)) return null
    return runner.prepared("SELECT * FROM ${quote(table)}") { statement ->
        var count = 0
        val names = List(statement.getColumnCount()) { statement.getColumnName(it) }
        while (statement.step()) {
            row(JsonObject(names.indices.associate { names[it] to statement.cell(it) }))
            count++
        }
        count
    }
}

/** The caller holds the transaction. */
internal suspend fun importRows(runner: StatementRunner, table: String, rows: Sequence<JsonObject>): ImportedRows? {
    if (table !in tables(runner)) return null
    val columns = columns(runner, table)
    var written = 0
    var refused = 0
    for (row in rows) {
        val kept = row.filterKeys { it in columns }
        if (kept.isEmpty()) {
            refused++
            continue
        }
        val names = kept.keys.toList()
        val sql = "INSERT OR REPLACE INTO ${quote(table)} (${names.joinToString { quote(it) }}) " +
            "VALUES (${names.joinToString { "?" }})"
        // A failed statement rolls back only itself (SQLite's ABORT), so the transaction goes on.
        val ok = try {
            runner.prepared(sql) { statement ->
                names.forEachIndexed { i, name -> statement.bindCell(i + 1, kept.getValue(name)) }
                statement.step()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) written++ else refused++
    }
    return ImportedRows(written, refused)
}

private suspend fun tables(runner: StatementRunner): Set<String> =
    runner.prepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
        buildSet { while (statement.step()) add(statement.getText(0)) }
    }

private suspend fun columns(runner: StatementRunner, table: String): Set<String> =
    runner.prepared("PRAGMA table_info(${quote(table)})") { statement ->
        val name = (0 until statement.getColumnCount()).first { statement.getColumnName(it) == "name" }
        buildSet { while (statement.step()) add(statement.getText(name)) }
    }

/** A cell as JSON: a blob as an object holding its base64, every other type as itself. */
internal fun SQLiteStatement.cell(index: Int): JsonElement = when (getColumnType(index)) {
    SQLITE_DATA_NULL -> JsonNull
    SQLITE_DATA_INTEGER -> JsonPrimitive(getLong(index))
    SQLITE_DATA_FLOAT -> JsonPrimitive(getDouble(index))
    SQLITE_DATA_BLOB -> JsonObject(mapOf(BLOB_KEY to JsonPrimitive(Base64.encode(getBlob(index)))))
    else -> JsonPrimitive(getText(index))
}

internal fun SQLiteStatement.bindCell(index: Int, value: JsonElement) {
    when (value) {
        is JsonNull -> bindNull(index)
        is JsonObject -> {
            val encoded = (value[BLOB_KEY] as? JsonPrimitive)?.takeIf { it.isString }
                ?: throw IllegalArgumentException("not a blob cell")
            bindBlob(index, Base64.decode(encoded.content))
        }
        is JsonPrimitive -> when {
            value.isString -> bindText(index, value.content)
            value.longOrNull != null -> bindLong(index, value.longOrNull!!)
            value.doubleOrNull != null -> bindDouble(index, value.doubleOrNull!!)
            value.booleanOrNull != null -> bindLong(index, if (value.booleanOrNull!!) 1L else 0L)
            else -> throw IllegalArgumentException("unreadable cell")
        }
        else -> throw IllegalArgumentException("not a cell")
    }
}

private const val BLOB_KEY = "\$blob"

/** SQLite's identifier quoting; table and column names come from this app's own allowlist and schema. */
private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

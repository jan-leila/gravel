package com.anopticlabs.gravel.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableRowsTest {
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
    private val runner = object : StatementRunner {
        override suspend fun <R> prepared(sql: String, block: (SQLiteStatement) -> R): R = connection.prepare(sql).use(block)
    }

    @AfterTest
    fun close() = connection.close()

    private suspend fun dump(table: String): List<JsonObject> = buildList { exportRows(runner, table) { add(it) } }

    @Test
    fun everyCellTypeSurvivesARoundTrip() = runTest {
        connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, n REAL, s TEXT, b BLOB, z TEXT)")
        connection.execSQL("INSERT INTO t VALUES (7, 1.5, 'it''s', x'00ff10', NULL)")
        val rows = dump("t")
        assertEquals(1, rows.size)
        connection.execSQL("DELETE FROM t")
        assertEquals(ImportedRows(1, 0), importRows(runner, "t", rows.asSequence()))
        connection.prepare("SELECT id, n, s, b, z FROM t").use {
            it.step()
            assertEquals(7L, it.getLong(0))
            assertEquals(1.5, it.getDouble(1))
            assertEquals("it's", it.getText(2))
            assertContentEquals(byteArrayOf(0, -1, 16), it.getBlob(3))
            assertEquals(true, it.isNull(4))
        }
    }

    @Test
    fun importUpsertsOnThePrimaryKeyAndKeepsOtherRows() = runTest {
        connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT)")
        connection.execSQL("INSERT INTO t VALUES (1, 'old'), (2, 'other')")
        val row = buildJsonObject { put("id", 1); put("s", "new") }
        assertEquals(ImportedRows(1, 0), importRows(runner, "t", sequenceOf(row)))
        assertEquals(
            listOf(buildJsonObject { put("id", 1); put("s", "new") }, buildJsonObject { put("id", 2); put("s", "other") }),
            dump("t"),
        )
    }

    @Test
    fun aColumnDroppedSinceIsIgnoredAndOneAddedTakesItsDefault() = runTest {
        connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, added TEXT NOT NULL DEFAULT 'd')")
        val row = buildJsonObject { put("id", 3); put("dropped", "x") }
        assertEquals(ImportedRows(1, 0), importRows(runner, "t", sequenceOf(row)))
        assertEquals(listOf(buildJsonObject { put("id", 3); put("added", "d") }), dump("t"))
    }

    @Test
    fun aRowTheSchemaRefusesIsCountedAndTheRestAreWritten() = runTest {
        connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, required TEXT NOT NULL)")
        val good = buildJsonObject { put("id", 1); put("required", "yes") }
        val bad = JsonObject(mapOf("id" to JsonPrimitive(2), "required" to JsonNull))
        val nothingKnown = buildJsonObject { put("unknown", 1) }
        assertEquals(ImportedRows(1, 2), importRows(runner, "t", sequenceOf(bad, good, nothingKnown)))
        assertEquals(listOf(good), dump("t"))
    }

    @Test
    fun aMissingTableIsReportedAsNull() = runTest {
        assertNull(exportRows(runner, "absent") {})
        assertNull(importRows(runner, "absent", emptySequence()))
    }

    @Test
    fun aQuoteInATableNameStaysInsideTheIdentifier() = runTest {
        connection.execSQL("CREATE TABLE \"a\"\"b\" (id INTEGER PRIMARY KEY)")
        connection.execSQL("INSERT INTO \"a\"\"b\" VALUES (1)")
        assertEquals(1, dump("a\"b").size)
    }
}

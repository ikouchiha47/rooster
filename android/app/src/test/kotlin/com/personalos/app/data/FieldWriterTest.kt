package com.personalos.app.data

import com.personalos.app.core.rules.FieldValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The field write contract: rows are append-only and the first write wins, so a
 * repeat ingest cannot rewrite a value (**frozen at ingest**, ADR 0003 §3).
 *
 * The fake models Room's `INSERT OR IGNORE` on the `(item_id, name)` primary
 * key. That conflict mode is a one-word annotation a JVM test cannot exercise
 * (no Robolectric or in-memory Room here), so the *real* guarantee lives in
 * `MigrationSchemaTest`'s composite-key assertion; this test pins the writer's
 * behaviour and the value mapping.
 */
class FieldWriterTest {
    private class FakeItemFieldDao : ItemFieldDao {
        val rows = mutableListOf<ItemFieldEntity>()
        var insertCalls = 0

        override suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long> {
            insertCalls++
            return fields.map { field ->
                if (rows.any { it.itemId == field.itemId && it.name == field.name }) {
                    -1L
                } else {
                    rows += field
                    1L
                }
            }
        }

        override suspend fun forItem(itemId: String): List<ItemFieldEntity> = rows.filter { it.itemId == itemId }

        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = rows.filter { it.itemId in itemIds }

        override suspend fun replaceAll(fields: List<ItemFieldEntity>) {
            for (row in fields) {
                rows.removeIf { it.itemId == row.itemId && it.name == row.name }
                rows += row
            }
        }

        override suspend fun deleteForItem(itemId: String) {
            rows.removeIf { it.itemId == itemId }
        }

        override suspend fun seriesWindow(
            sourceId: String,
            field: String,
            limit: Int,
        ): List<SeriesSample> = emptyList()
    }

    @Test
    fun `a repeat write does not overwrite a frozen value`() =
        runBlocking {
            val dao = FakeItemFieldDao()
            val writer = FieldWriter(dao)

            assertEquals(2, writer.write("i1", mapOf("sender" to FieldValue.Str("VM-HDFCBK-S"), "amount" to FieldValue.Num(12000.0))))
            assertEquals("a second write of the same keys is ignored, not applied", 0, writer.write("i1", mapOf("sender" to FieldValue.Str("OTHER"), "amount" to FieldValue.Num(1.0))))

            assertEquals(2, dao.rows.size)
            val stored = dao.rows.associateBy { it.name }
            assertEquals(FieldValue.Str("VM-HDFCBK-S"), stored.getValue("sender").toFieldValue())
            assertEquals(FieldValue.Num(12000.0), stored.getValue("amount").toFieldValue())
        }

    @Test
    fun `each typed value maps to its own column`() =
        runBlocking {
            val dao = FakeItemFieldDao()
            FieldWriter(dao).write(
                "i1",
                mapOf(
                    "amount" to FieldValue.Num(45.5),
                    "sender" to FieldValue.Str("VM-HDFCBK-S"),
                    "flagged" to FieldValue.Flag(true),
                ),
            )

            val rows = dao.rows.associateBy { it.name }
            assertEquals(45.5, rows.getValue("amount").valueNum!!, 0.001)
            assertEquals("VM-HDFCBK-S", rows.getValue("sender").valueText)
            assertEquals(true, rows.getValue("flagged").valueFlag)
            // Exactly one column is set per row.
            assertEquals(null, rows.getValue("amount").valueText)
            assertEquals(null, rows.getValue("amount").valueFlag)
        }

    @Test
    fun `an empty write touches nothing`() =
        runBlocking {
            val dao = FakeItemFieldDao()
            assertEquals(0, FieldWriter(dao).writeAll(emptyList()))
            assertEquals(0, dao.insertCalls)
        }
}

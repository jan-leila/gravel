package com.anopticlabs.gravel.backup

import com.anopticlabs.gravel.notebook.backup.BackupProblem
import com.anopticlabs.gravel.notebook.backup.InvalidBackupException
import com.anopticlabs.gravel.notebook.backup.SealedFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppBackupEntriesTest {
    private val password = "a long enough backup password".toCharArray()
    private val manifest = AppBackupManifest(AppBackupFormat.VERSION, 1L, "0.6.0", mapOf("t" to 2), notebook = false)

    private fun sealedZip(vararg entries: Pair<String, ByteArray>, padding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(SealedFile.create(out, AppBackupFormat.MAGIC, password)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("filler"))
            zip.write(ByteArray(padding) { (it * 7).toByte() })
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun manifestBytes(m: AppBackupManifest = manifest) =
        AppBackupFormat.json.encodeToString(AppBackupManifest.serializer(), m).encodeToByteArray()

    private fun verify(data: ByteArray): AppBackupManifest {
        val input = ByteArrayInputStream(data)
        val header = SealedFile.readHeader(input, AppBackupFormat.MAGIC)
        return verifyBackup(SealedFile.open(input, header, SealedFile.deriveKey(header, password)))
    }

    @Test
    fun aWholeBackupVerifiesAndReturnsItsManifest() {
        assertEquals(manifest, verify(sealedZip("a" to byteArrayOf(1), AppBackupFormat.MANIFEST to manifestBytes())))
    }

    @Test
    fun aBackupCutShortIsDamagedEvenWhereTheZipWouldStillRead() {
        // Random bytes keep the second file over several segments after compression.
        val data = sealedZip(AppBackupFormat.MANIFEST to manifestBytes(), padding = 0)
        val big = sealedZip(AppBackupFormat.MANIFEST to manifestBytes(), "noise" to java.util.Random(1).let { r -> ByteArray(200_000).also(r::nextBytes) })
        for (cut in listOf(data.copyOf(data.size - 1), big.copyOf(big.size - 20), big.copyOf(big.size / 2))) {
            assertFailsWith<InvalidBackupException> { verify(cut) }
        }
    }

    @Test
    fun aBackupWithoutAManifestIsDamaged() {
        assertEquals(BackupProblem.DAMAGED, assertFailsWith<InvalidBackupException> { verify(sealedZip("a" to byteArrayOf(1))) }.problem)
    }

    @Test
    fun aBackupFromANewerVersionIsRefusedAsSuch() {
        val newer = manifestBytes(manifest.copy(version = AppBackupFormat.VERSION + 1))
        assertEquals(BackupProblem.NEWER_FORMAT, assertFailsWith<InvalidBackupException> { verify(sealedZip(AppBackupFormat.MANIFEST to newer)) }.problem)
    }

    @Test
    fun appIdsMustBeUuidsInTheirOwnDirectory() {
        val id = "0863fc6a-66c5-4f62-ab8a-82ed00a98b5d"
        assertEquals(id, appIdIn("pkjs/$id.json", AppBackupFormat.PKJS_DIR, ".json"))
        assertNull(appIdIn("pkjs/../shared_prefs/x.json", AppBackupFormat.PKJS_DIR, ".json"))
        assertNull(appIdIn("pkjs/$id/../x.json", AppBackupFormat.PKJS_DIR, ".json"))
        assertNull(appIdIn("pbw/$id.json", AppBackupFormat.PKJS_DIR, ".json"))
        assertNull(appIdIn("pkjs/$id.pbw", AppBackupFormat.PKJS_DIR, ".json"))
    }

    @Test
    fun onlyTheTablesABackupHoldsAreRestored() {
        assertEquals("libpebble3" to "LockerEntryEntity", tableIn("tables/libpebble3/LockerEntryEntity.jsonl"))
        assertEquals("coreapp" to "AppstoreSource", tableIn("tables/coreapp/AppstoreSource.jsonl"))
        assertNull(tableIn("tables/libpebble3/KnownWatchItem.jsonl"))
        assertNull(tableIn("tables/coreapp/LockerEntryEntity.jsonl"))
        assertNull(tableIn("tables/libpebble3/LockerEntryEntity.json"))
        assertNull(tableIn("tables/x/libpebble3/LockerEntryEntity.jsonl"))
    }

    @Test
    fun jsonLinesReadsEachRowAndRefusesOneOverTheCap() {
        val rows = "{\"a\":1}\n{\"a\":2}\n\n{\"a\":3}".byteInputStream().jsonLines().toList()
        assertEquals(listOf(1, 2, 3), rows.map { it["a"].toString().toInt() })
        assertFailsWith<InvalidBackupException> { "{\"a\":\"${"x".repeat(100)}\"}".byteInputStream().jsonLines(maxLineBytes = 50).toList() }
        assertFailsWith<InvalidBackupException> { "not json\n".byteInputStream().jsonLines().toList() }
    }

    @Test
    fun readCappedRefusesAnEntryOverItsCap() {
        assertFailsWith<InvalidBackupException> { ByteArray(11).inputStream().readCapped(10) }
        assertEquals(10, ByteArray(10).inputStream().readCapped(10).size)
    }
}

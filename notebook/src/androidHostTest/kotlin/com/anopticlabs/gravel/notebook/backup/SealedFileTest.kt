package com.anopticlabs.gravel.notebook.backup

import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.HEADER_LEN
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.SEGMENT_BYTES
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat.TAG_LEN
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SealedFileTest {
    private val password = "correct horse battery".toCharArray()
    private val magic = "TEST".encodeToByteArray()

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun seal(plain: ByteArray, m: ByteArray = magic): ByteArray {
        val out = ByteArrayOutputStream()
        SealedFile.create(out, m, password).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(data: ByteArray, m: ByteArray = magic, pw: CharArray = password): ByteArray {
        val input = ByteArrayInputStream(data)
        val header = SealedFile.readHeader(input, m)
        return SealedFile.open(input, header, SealedFile.deriveKey(header, pw)).readBytes()
    }

    @Test
    fun roundTripsAcrossSegmentBoundaries() {
        for (size in listOf(0, 1, SEGMENT_BYTES, SEGMENT_BYTES + 1, 3 * SEGMENT_BYTES + 17)) {
            assertContentEquals(bytes(size), open(seal(bytes(size))), "size $size")
        }
    }

    @Test
    fun refusesAnotherMagicIncludingANotebookArchive() {
        assertEquals(BackupProblem.NOT_A_BACKUP, assertFailsWith<InvalidBackupException> { open(seal(bytes(10)), "OTHR".encodeToByteArray()) }.problem)
        val notebook = ByteArrayOutputStream().also { ArchiveWriter.open(it, password).finish() }.toByteArray()
        assertEquals(BackupProblem.NOT_A_BACKUP, assertFailsWith<InvalidBackupException> { open(notebook) }.problem)
    }

    @Test
    fun aHeaderWithItsMagicSwappedFailsAuthentication() {
        val data = seal(bytes(10))
        "OTHR".encodeToByteArray().copyInto(data)
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, assertFailsWith<InvalidBackupException> { open(data, "OTHR".encodeToByteArray()) }.problem)
    }

    @Test
    fun wrongPasswordIsReported() {
        val e = assertFailsWith<InvalidBackupException> { open(seal(bytes(10)), pw = "another long passphrase".toCharArray()) }
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, e.problem)
    }

    @Test
    fun aFileCutAtASegmentBoundaryIsDamaged() {
        val data = seal(bytes(2 * SEGMENT_BYTES + 5))
        val cut = data.copyOf(HEADER_LEN + 2 * (SEGMENT_BYTES + TAG_LEN))
        assertEquals(BackupProblem.DAMAGED, assertFailsWith<InvalidBackupException> { open(cut) }.problem)
    }

    @Test
    fun aStreamNeverClosedReadsAsCutShort() {
        val out = ByteArrayOutputStream()
        SealedFile.create(out, magic, password).write(bytes(SEGMENT_BYTES + 5))
        assertFailsWith<InvalidBackupException> { open(out.toByteArray()) }
    }
}

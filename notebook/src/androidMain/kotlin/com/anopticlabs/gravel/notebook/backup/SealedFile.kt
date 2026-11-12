package com.anopticlabs.gravel.notebook.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The archive's sealing without its record framing, for another kind of file: the same header
 * layout, password KDF and STREAM segments, under a magic of the file's own. The magic is in the
 * header and so in every segment's AAD, which keeps a file of one kind from opening as another.
 */
object SealedFile {
    /**
     * Derives the key from [password] and writes the header to [out]. Closing the stream returned
     * seals the last segment and closes [out]; until then a reader sees the file as cut short.
     */
    @Throws(IOException::class)
    fun create(out: OutputStream, magic: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): OutputStream {
        val chars = requireNotNull(ArchiveCrypto.normalizedPassword(password)) { "password is not valid text" }
        try {
            require(ArchiveCrypto.isLongEnough(chars)) { "password shorter than ${ArchiveFormat.MIN_PASSWORD_LENGTH} characters" }
            val salt = ByteArray(ArchiveFormat.SALT_LEN).also { random.nextBytes(it) }
            val prefix = ByteArray(ArchiveFormat.NONCE_PREFIX_LEN).also { random.nextBytes(it) }
            val header = ArchiveCrypto.header(salt, prefix, ArchiveFormat.KDF_PBKDF2_SHA256, magic)
            val key = ArchiveCrypto.deriveKey(chars, salt)
            out.write(header)
            return SealedOutputStream(out, SegmentSealer(out, key, header, prefix))
        } finally {
            chars.fill('\u0000')
        }
    }

    /** Reads and checks the header of a file with [magic]; only password-sealed files are accepted. */
    @Throws(IOException::class)
    fun readHeader(input: InputStream, magic: ByteArray): ByteArray {
        val header = ArchiveReader.readHeader(input, magic)
        if (ArchiveReader.kdfOf(header) != ArchiveFormat.KDF_PBKDF2_SHA256) {
            throw InvalidBackupException(BackupProblem.NEWER_FORMAT, "key derivation this app does not know for this file")
        }
        return header
    }

    /** The slow step: PBKDF2 over [password] and the header's salt. */
    fun deriveKey(header: ByteArray, password: CharArray): ArchiveKey = ArchiveReader.deriveKey(header, password)

    /**
     * The plaintext after [header], which [input] has already been read past. Every byte returned
     * comes from a segment that authenticated; only a read that returns -1 proves nothing was cut
     * off the end, so a caller acts on the contents only after reading to the end.
     */
    @Throws(IOException::class)
    fun open(input: InputStream, header: ByteArray, key: ArchiveKey): InputStream =
        SealedInputStream(ArchiveReader.openSegments(input, header, key))
}

private class SealedOutputStream(private val out: OutputStream, private val sealer: SegmentSealer) : OutputStream() {
    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("sealed stream closed")
        sealer.write(b, off, len)
    }

    override fun close() {
        if (closed) return
        closed = true
        out.use { sealer.finish() }
    }
}

private class SealedInputStream(private val opener: SegmentOpener) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = opener.read(b, off, len)
}

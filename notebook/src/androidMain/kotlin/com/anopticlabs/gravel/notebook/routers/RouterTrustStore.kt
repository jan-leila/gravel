package com.anopticlabs.gravel.notebook.routers

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * The user's changes to the trusted certificates ([TrustEntries]), as JSON in the no-backup
 * directory: the trust names signers on this phone, so it stays with it. A file that cannot be
 * read or parsed reads as no changes, which leaves the built-in list alone and adds nothing.
 */
class RouterTrustStore(
    dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val defaults: Set<String> = DEFAULT_TRUSTED_ROUTER_SIGNERS,
) {
    private val file = DeviceStateFile(dir, FILE_NAME, maxChars = MAX_CHARS)
    private val writes = Mutex()
    private val _entries = MutableStateFlow<TrustEntries?>(null)

    /** Null until [load] or a write. */
    val entries: StateFlow<TrustEntries?> = _entries.asStateFlow()

    /** Blocks on a file read. */
    fun read(): TrustEntries {
        val json = file.read() ?: return TrustEntries.EMPTY
        return try {
            NotebookJson.decodeFromString(TrustEntries.serializer(), json).cleaned()
        } catch (e: SerializationException) {
            logger.w { "router trust file unreadable: ${e::class.simpleName}" }
            TrustEntries.EMPTY
        } catch (e: IllegalArgumentException) {
            logger.w { "router trust file unreadable: ${e::class.simpleName}" }
            TrustEntries.EMPTY
        }
    }

    suspend fun load(): TrustEntries = withContext(Dispatchers.IO) { read().also { _entries.value = it } }

    /** The list in force now; blocks on a file read. */
    fun certificates(): List<TrustedCertificate> = RouterTrust.certificates(read(), defaults)

    fun trustedDigests(): Set<String> = RouterTrust.trustedDigests(read(), defaults)

    /**
     * Trusts [sha256]: a built-in certificate the user removed is restored, any other is added, or
     * renamed when it is there. False when the digest is malformed, the list is full, or the file
     * could not be written.
     */
    suspend fun add(sha256: String, label: String?): Boolean {
        val digest = RouterTrust.normalizeDigest(sha256) ?: return false
        return write { entries ->
            if (digest in defaults) {
                entries.copy(removedDefaults = entries.removedDefaults - digest)
            } else {
                val others = entries.added.filterNot { it.sha256 == digest }
                if (others.size >= TrustEntries.MAX_ENTRIES) null else entries.copy(added = others + TrustedSigner(digest, RouterTrust.normalizeLabel(label), clock()))
            }
        }
    }

    /** Stops trusting [sha256]; a built-in certificate is remembered as removed. */
    suspend fun remove(sha256: String): Boolean {
        val digest = RouterTrust.normalizeDigest(sha256) ?: return false
        return write { entries ->
            val added = entries.added.filterNot { it.sha256 == digest }
            if (digest in defaults) entries.copy(added = added, removedDefaults = entries.removedDefaults + digest) else entries.copy(added = added)
        }
    }

    /** Everything at once, as an import does. */
    suspend fun replace(entries: TrustEntries): Boolean = write { entries }

    private suspend fun write(change: (TrustEntries) -> TrustEntries?): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            val next = change(read())?.cleaned() ?: return@withLock false
            val saved = if (next == TrustEntries.EMPTY) file.clear() else file.write(NotebookJson.encodeToString(TrustEntries.serializer(), next))
            if (saved) _entries.value = next
            saved
        }
    }

    companion object {
        const val FILE_NAME = "notebook_router_trust"

        /** The caps' worth of entries of a digest, a label and a time, and of removed digests. */
        const val MAX_CHARS = 32 * 1024

        private val logger = Logger.withTag("RouterTrustStore")
    }
}

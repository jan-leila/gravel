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
import kotlinx.serialization.builtins.ListSerializer

/**
 * The apps the user trusts to receive routed text, as a JSON list in the no-backup directory: the
 * trust names signers on this phone, so it stays with it. A file that cannot be read or parsed
 * reads as no entries, which trusts nothing.
 */
class RouterTrustStore(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = DeviceStateFile(dir, FILE_NAME, maxChars = MAX_CHARS)
    private val writes = Mutex()
    private val _entries = MutableStateFlow<List<TrustedRouterApp>?>(null)

    /** Null until [load] or a write. */
    val entries: StateFlow<List<TrustedRouterApp>?> = _entries.asStateFlow()

    /** Blocks on a file read. */
    fun read(): List<TrustedRouterApp> {
        val json = file.read() ?: return emptyList()
        return try {
            NotebookJson.decodeFromString(ListSerializer(TrustedRouterApp.serializer()), json)
                .filter { RouterTrust.normalizeDigest(it.sha256) == it.sha256 && it.packageName.isNotEmpty() }
        } catch (e: SerializationException) {
            logger.w { "router trust file unreadable: ${e::class.simpleName}" }
            emptyList()
        } catch (e: IllegalArgumentException) {
            logger.w { "router trust file unreadable: ${e::class.simpleName}" }
            emptyList()
        }
    }

    suspend fun load(): List<TrustedRouterApp> = withContext(Dispatchers.IO) { read().also { _entries.value = it } }

    /** Trusts [packageName] by [sha256], replacing an earlier entry for the package; false when the file could not be written. */
    suspend fun trust(packageName: String, sha256: String): Boolean {
        val digest = RouterTrust.normalizeDigest(sha256) ?: return false
        return write { entries -> entries.filterNot { it.packageName == packageName } + TrustedRouterApp(packageName, digest, clock()) }
    }

    /** Trusts [packageName] by each of [sha256s], for an app with several signers. */
    suspend fun trustAll(packageName: String, sha256s: List<String>): Boolean {
        val digests = sha256s.map { RouterTrust.normalizeDigest(it) ?: return false }
        return write { entries -> entries.filterNot { it.packageName == packageName } + digests.map { TrustedRouterApp(packageName, it, clock()) } }
    }

    suspend fun forget(packageName: String): Boolean = write { entries -> entries.filterNot { it.packageName == packageName } }

    private suspend fun write(change: (List<TrustedRouterApp>) -> List<TrustedRouterApp>): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            val next = change(read())
            val saved = if (next.isEmpty()) file.clear() else file.write(NotebookJson.encodeToString(ListSerializer(TrustedRouterApp.serializer()), next))
            if (saved) _entries.value = next
            saved
        }
    }

    companion object {
        const val FILE_NAME = "notebook_router_trust"

        /** Dozens of entries of a package name, a digest and a time. */
        const val MAX_CHARS = 16 * 1024

        private val logger = Logger.withTag("RouterTrustStore")
    }
}

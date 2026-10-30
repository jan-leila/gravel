package com.anopticlabs.gravel.notebook.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anopticlabs.gravel.notebook.backup.BackupDocumentOps
import com.anopticlabs.gravel.notebook.routers.InstalledRouter
import com.anopticlabs.gravel.notebook.routers.InstalledRouters
import com.anopticlabs.gravel.notebook.routers.RouterTrust
import com.anopticlabs.gravel.notebook.routers.RouterTrustStore
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.RoutingExport
import com.anopticlabs.gravel.notebook.routers.RoutingStoreState
import com.anopticlabs.gravel.notebook.routers.TrustEntries
import com.anopticlabs.gravel.notebook.routers.TrustedCertificate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * An installed app that declares routers, as the screen lists it. [namespace] is its signer, the
 * namespace its routers are in; [trusted] when that certificate is on the list in force.
 */
data class RouterApp(
    val packageName: String,
    val appName: String,
    val namespace: String,
    val routers: List<InstalledRouter>,
    val trusted: Boolean,
)

/**
 * [store] is null until the first load; [error] is the last refused save or failed write and
 * [notice] the last export or import that went through, each until cleared.
 */
data class RoutingUi(
    val store: RoutingStoreState? = null,
    val apps: List<RouterApp> = emptyList(),
    val certificates: List<TrustedCertificate> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,
)

/**
 * The Text Routing screen's state: the stored routing, the trusted certificates, the apps with
 * routers, and the export and import of routing and trust together through [documents]
 * (documents the system picker handed over as [H]); one action at a time.
 */
class RoutingViewModel<H : Any>(
    private val store: RoutingConfigStore,
    private val trust: RouterTrustStore,
    private val installed: InstalledRouters,
    private val documents: BackupDocumentOps<H>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(RoutingUi())
    val ui: StateFlow<RoutingUi> = _ui.asStateFlow()

    /** Reads the routing, the trust and the installed routers again. */
    fun load() = run {
        val state = store.load()
        trust.load()
        refreshTrust()
        _ui.update { it.copy(store = state) }
    }

    /** Validates and stores [config]; a config the store refuses leaves the stored routing as it was and sets [RoutingUi.error]. */
    fun save(config: RoutingConfig) = run {
        try {
            store.write(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(error = e.message ?: SAVE_FAILED) }
            return@run
        } catch (e: Exception) {
            _ui.update { it.copy(error = SAVE_FAILED) }
            return@run
        }
        _ui.update { it.copy(store = RoutingStoreState.Loaded(config)) }
    }

    /** Back to the default routing, whatever the file holds; the trust stays. */
    fun reset() = run {
        try {
            store.reset()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = RESET_FAILED) }
            return@run
        }
        _ui.update { it.copy(store = RoutingStoreState.Loaded(RoutingConfig.DEFAULT)) }
    }

    /** Trusts the certificate [sha256], called [label]; the screen checked the digest's shape and showed it first. */
    fun addCertificate(sha256: String, label: String?) = run {
        val digest = RouterTrust.normalizeDigest(sha256)
        if (digest == null) {
            _ui.update { it.copy(error = BAD_DIGEST) }
            return@run
        }
        val listed = trust.certificates()
        if (listed.none { it.sha256 == digest } && listed.size >= TrustEntries.MAX_ENTRIES) {
            _ui.update { it.copy(error = TOO_MANY) }
            return@run
        }
        if (!trust.add(digest, label)) _ui.update { it.copy(error = TRUST_FAILED) }
        refreshTrust()
    }

    fun removeCertificate(sha256: String) = run {
        if (!trust.remove(sha256)) _ui.update { it.copy(error = TRUST_FAILED) }
        refreshTrust()
    }

    /** Trusts [app] by its signer, named after it; the screen asked the user first, showing the signer. */
    fun trustApp(app: RouterApp) = addCertificate(app.namespace, app.appName)

    fun forgetApp(app: RouterApp) = removeCertificate(app.namespace)

    /** Writes the routing and the trust to the document [handle] the user picked. */
    fun export(handle: H) = run {
        val text = try {
            RoutingExport.encode(store.read(), trust.read())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = EXPORT_FAILED) }
            return@run
        }
        try {
            documents.openWrite(handle).use { it.write(text.encodeToByteArray()) }
        } catch (e: IOException) {
            _ui.update { it.copy(error = EXPORT_FAILED) }
            return@run
        }
        _ui.update { it.copy(notice = EXPORTED) }
    }

    /** Replaces the routing and the trust with the document [handle] holds; a file that is refused changes nothing. */
    fun import(handle: H) = run {
        val parsed = try {
            RoutingExport.decode(documents.openRead(handle).use { readUpTo(it, RoutingExport.MAX_CHARS + 1) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(error = e.message ?: IMPORT_FAILED) }
            return@run
        } catch (e: IOException) {
            _ui.update { it.copy(error = IMPORT_FAILED) }
            return@run
        }
        try {
            store.write(parsed.routing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = IMPORT_FAILED) }
            return@run
        }
        if (!trust.replace(parsed.trust)) _ui.update { it.copy(error = TRUST_FAILED) }
        refreshTrust()
        _ui.update { it.copy(store = RoutingStoreState.Loaded(parsed.routing), notice = IMPORTED) }
    }

    /** The system has no app to pick a document with. */
    fun pickerUnavailable() = _ui.update { it.copy(error = NO_PICKER) }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    private fun refreshTrust() {
        val certificates = trust.certificates()
        val trusted = certificates.mapTo(mutableSetOf()) { it.sha256 }
        val apps = installed.discover().groupBy { it.packageName }.map { (packageName, routers) ->
            val namespace = routers.first().namespace
            RouterApp(
                packageName,
                routers.first().appName,
                namespace,
                routers.sortedBy { it.label },
                trusted = RouterTrust.isTrusted(namespace, trusted) { installed.signedBy(packageName, it) },
            )
        }.sortedBy { it.appName }
        _ui.update { it.copy(apps = apps, certificates = certificates) }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        const val SAVE_FAILED = "The routing couldn't be saved."
        const val RESET_FAILED = "The routing couldn't be reset."
        const val TRUST_FAILED = "The trusted certificates couldn't be saved."
        const val BAD_DIGEST = "A certificate is its SHA-256: 64 hex digits."
        const val TOO_MANY = "There can be at most ${TrustEntries.MAX_ENTRIES} trusted certificates."
        const val EXPORT_FAILED = "The routing couldn't be exported."
        const val IMPORT_FAILED = "The file couldn't be read."
        const val NO_PICKER = "No app on this phone can pick a file."
        const val EXPORTED = "The routing and the trusted certificates were exported."
        const val IMPORTED = "The routing and the trusted certificates were imported."

        /** Up to [max] bytes of [input] as text; past that the file is not an export, whatever it is. */
        private fun readUpTo(input: InputStream, max: Int): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= max) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            require(out.size() <= max) { "The file is too large to be a routing export." }
            return out.toByteArray().decodeToString()
        }
    }
}

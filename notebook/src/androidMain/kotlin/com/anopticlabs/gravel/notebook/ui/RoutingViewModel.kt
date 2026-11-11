package com.anopticlabs.gravel.notebook.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anopticlabs.gravel.notebook.backup.BackupDocumentOps
import com.anopticlabs.gravel.notebook.capture.RecordingLocation
import kotlinx.serialization.json.decodeFromJsonElement
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import com.anopticlabs.gravel.notebook.routers.RoutingSecrets
import com.anopticlabs.gravel.notebook.routers.RoutingDiff
import com.anopticlabs.gravel.notebook.routers.RedactedRouting
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.llm.LlmJson
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
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
import kotlinx.coroutines.withContext
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

/** A routing the text model proposed from the user's description, with what changed in it, for the user to keep or drop. */
data class Proposal(val config: RoutingConfig, val lines: List<String>)

/**
 * [store] is null until the first load; [error] is the last refused save or failed write and
 * [notice] the last export or import that went through, each until cleared; [proposal] is the
 * text model's answer to a described change, until applied or discarded.
 */
data class RoutingUi(
    val store: RoutingStoreState? = null,
    val apps: List<RouterApp> = emptyList(),
    val certificates: List<TrustedCertificate> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,
    val proposal: Proposal? = null,
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
    private val location: RecordingLocation = RecordingLocation.NONE,
    private val text: CaptureText? = null,
    private val processing: () -> Boolean = { false },
) : ViewModel() {
    /** Where the phone is now, for a place the user is at; null when it is not known or not allowed. */
    suspend fun currentLocation(): RoutedLocation? = withContext(io) { location.current() }

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

    /**
     * Asks the text model for the routing the user described, with the secrets swapped out before
     * it sees the routing and back afterwards, and validated before it is shown; nothing is
     * stored until [applyProposal]. One retry with the reason when the first answer is refused.
     */
    fun describeChange(instruction: String) = run {
        val engine = text
        if (engine == null) {
            _ui.update { it.copy(error = EDIT_UNAVAILABLE) }
            return@run
        }
        if (processing()) {
            _ui.update { it.copy(error = EDIT_BUSY) }
            return@run
        }
        val asked = instruction.trim()
        if (asked.isEmpty() || asked.length > MAX_INSTRUCTION_CHARS) {
            _ui.update { it.copy(error = EDIT_INSTRUCTION) }
            return@run
        }
        val current = try {
            store.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = EDIT_FAILED) }
            return@run
        }
        val redacted = RoutingSecrets.redact(current)
        val json = NotebookJson.encodeToString(RoutingConfig.serializer(), redacted.config)
        if (json.length > MAX_EDIT_JSON_CHARS) {
            _ui.update { it.copy(error = EDIT_TOO_LARGE) }
            return@run
        }
        try {
            engine.checkReady()
            engine.prepare()
            var problem: String? = null
            repeat(EDIT_ATTEMPTS) {
                val raw = engine.generate(Prompts.ROUTING_EDIT_SYSTEM, Prompts.routingEditPrompt(json, asked, problem), EDIT_REPLY_TOKENS)
                when (val outcome = proposalFrom(raw, current, redacted)) {
                    is Proposal -> {
                        _ui.update { it.copy(proposal = outcome) }
                        return@run
                    }
                    is String -> problem = outcome
                }
            }
            _ui.update { it.copy(error = EDIT_FAILED) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaptureProcessingException) {
            _ui.update { it.copy(error = e.message ?: EDIT_UNAVAILABLE) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = EDIT_FAILED) }
        } finally {
            try {
                engine.release()
            } catch (e: Exception) {
                // The model is freed on the next capture anyway.
            }
        }
    }

    /** Stores the proposed routing; the proposal goes whether the save went through or not, since the error says. */
    fun applyProposal() {
        val proposal = _ui.value.proposal ?: return
        _ui.update { it.copy(proposal = null) }
        save(proposal.config)
    }

    fun discardProposal() = _ui.update { it.copy(proposal = null) }

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
        const val EDIT_UNAVAILABLE = "The text model is not set up."
        const val EDIT_BUSY = "Wait for the recordings being processed to finish."
        const val MAX_INSTRUCTION_CHARS = 1_000
        const val EDIT_INSTRUCTION = "Describe the change in up to $MAX_INSTRUCTION_CHARS characters."
        const val EDIT_TOO_LARGE = "The routing is too large to edit this way."
        const val EDIT_FAILED = "The model couldn't make that change."
        const val MAX_EDIT_JSON_CHARS = 24 * 1024
        const val EDIT_REPLY_TOKENS = 2_048
        const val EDIT_ATTEMPTS = 2

        /** The proposal in the model's [raw] answer, or the reason it is refused, for the retry prompt. */
        internal fun proposalFrom(raw: String, current: RoutingConfig, redacted: RedactedRouting): Any {
            val element = LlmJson.extract(LlmJson.cleanThinkAndFences(raw)) ?: return "the answer was not a JSON object"
            val decoded = try {
                NotebookJson.decodeFromJsonElement(RoutingConfig.serializer(), element)
            } catch (e: IllegalArgumentException) {
                return "the JSON did not have the routing's shape: ${e.message?.take(200)}"
            }
            return try {
                val restored = RoutingSecrets.restore(decoded, redacted)
                restored.requireWellFormed()
                Proposal(restored, RoutingDiff.describe(current, restored))
            } catch (e: IllegalArgumentException) {
                e.message ?: "the routing was refused"
            }
        }

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

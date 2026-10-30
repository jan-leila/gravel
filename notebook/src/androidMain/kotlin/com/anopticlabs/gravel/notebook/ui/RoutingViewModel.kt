package com.anopticlabs.gravel.notebook.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anopticlabs.gravel.notebook.routers.InstalledRouter
import com.anopticlabs.gravel.notebook.routers.InstalledRouters
import com.anopticlabs.gravel.notebook.routers.RouterTrust
import com.anopticlabs.gravel.notebook.routers.RouterTrustStore
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.RoutingStoreState
import com.anopticlabs.gravel.notebook.routers.TrustedRouterApp
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
 * namespace its routers are in; [trusted] by the built-in list or the user's entry; [byUser] when
 * the user's entry is what trusts it, so it can be withdrawn.
 */
data class RouterApp(
    val packageName: String,
    val appName: String,
    val namespace: String,
    val routers: List<InstalledRouter>,
    val trusted: Boolean,
    val byUser: Boolean,
)

/** [store] is null until the first load; [error] is the last refused save or failed write, until [RoutingViewModel.clearError]. */
data class RoutingUi(
    val store: RoutingStoreState? = null,
    val apps: List<RouterApp> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
)

/** The Text Routing screen's state: the stored routing, the apps with routers and their trust, one action at a time. */
class RoutingViewModel(
    private val store: RoutingConfigStore,
    private val trust: RouterTrustStore,
    private val installed: InstalledRouters,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(RoutingUi())
    val ui: StateFlow<RoutingUi> = _ui.asStateFlow()

    /** Reads the routing, the trust entries and the installed routers again. */
    fun load() = run {
        val state = store.load()
        val apps = apps(trust.load())
        _ui.update { it.copy(store = state, apps = apps) }
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

    /** Back to the default routing, whatever the file holds. */
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

    /** Trusts [app] by its signer; the screen asked the user first, showing it. */
    fun trustApp(app: RouterApp) = run {
        if (!trust.trust(app.packageName, app.namespace)) _ui.update { it.copy(error = TRUST_FAILED) }
        refreshApps()
    }

    fun forgetApp(app: RouterApp) = run {
        if (!trust.forget(app.packageName)) _ui.update { it.copy(error = TRUST_FAILED) }
        refreshApps()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun refreshApps() {
        val apps = apps(trust.read())
        _ui.update { it.copy(apps = apps) }
    }

    private fun apps(entries: List<TrustedRouterApp>): List<RouterApp> =
        installed.discover().groupBy { it.packageName }.map { (packageName, routers) ->
            val namespace = routers.first().namespace
            RouterApp(
                packageName,
                routers.first().appName,
                namespace,
                routers.sortedBy { it.label },
                trusted = RouterTrust.isTrusted(packageName, listOf(namespace), entries, { installed.signedBy(packageName, it) }),
                byUser = entries.any { it.packageName == packageName },
            )
        }.sortedBy { it.packageName }

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
        const val TRUST_FAILED = "The trust list couldn't be saved."
    }
}

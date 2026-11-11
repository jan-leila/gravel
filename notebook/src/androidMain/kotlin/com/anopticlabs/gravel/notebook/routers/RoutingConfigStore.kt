package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import com.anopticlabs.gravel.notebook.storage.Cryptographer
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.EncryptedJsonFile
import com.anopticlabs.gravel.notebook.storage.PersistedFormatException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the store last read, for the screens that show the routing. */
sealed interface RoutingStoreState {
    data class Loaded(val config: RoutingConfig) : RoutingStoreState

    /** Damaged, or written in a format this build does not read; [RoutingConfigStore.reset] starts over from the default. */
    data object Unreadable : RoutingStoreState

    /** The key or the disk did not answer this time. */
    data object Unavailable : RoutingStoreState
}

/**
 * The `routing` store file: the [RoutingConfig] as one encrypted JSON value under the Notebook
 * root, so a Network router's token is sealed like the rest of the Notebook. Absent reads as
 * [RoutingConfig.DEFAULT]; a file that is present but cannot be read is never taken for the
 * default, because that would quietly send every recording to the Notebook. Settings, so the
 * backup leaves it out; a Start over sets it aside with the other store files.
 */
class RoutingConfigStore(root: File, crypto: Cryptographer) {
    private val file = EncryptedJsonFile(
        EncryptedFiles(root, crypto),
        File(root, FILE_NAME),
        RoutingConfig.serializer(),
        MAX_BYTES,
        { RoutingConfig.DEFAULT },
        RoutingConfig::requireWellFormed,
    )

    private val _state = MutableStateFlow<RoutingStoreState?>(null)

    /** Null until [load] has run once. */
    val state: StateFlow<RoutingStoreState?> = _state.asStateFlow()

    /** Strict: a file that cannot be opened, parsed or that this build refuses throws. Blocks on file and Keystore I/O. */
    fun read(): RoutingConfig = file.read(strict = true)

    suspend fun load(): RoutingStoreState = withContext(Dispatchers.IO) {
        val state = try {
            RoutingStoreState.Loaded(read())
        } catch (e: CorruptFileException) {
            RoutingStoreState.Unreadable
        } catch (e: PersistedFormatException) {
            RoutingStoreState.Unreadable
        } catch (e: Exception) {
            RoutingStoreState.Unavailable
        }
        _state.value = state
        state
    }

    /** Refuses, with nothing written, a config [RoutingConfig.requireWellFormed] refuses. */
    suspend fun write(config: RoutingConfig) {
        config.requireWellFormed()
        file.update { config }
        _state.value = RoutingStoreState.Loaded(config)
    }

    /** Sets the file aside whatever it holds, so the routing reads as the default again. */
    suspend fun reset() {
        file.setAside()
        _state.value = RoutingStoreState.Loaded(RoutingConfig.DEFAULT)
    }

    companion object {
        const val FILE_NAME = "routing"

        /** Sixty-four routers with a page of rules each stay far under this. */
        const val MAX_BYTES = 64L * 1024
    }
}

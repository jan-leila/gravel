package com.anopticlabs.gravel.gitsource

import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/** What a PBW declares about itself. [capabilities] holds `appinfo.json`'s list, plus [PKJS_CAPABILITY] when it carries phone-side JavaScript. */
data class PbwFacts(val uuid: Uuid, val title: String, val version: String, val capabilities: Set<String>)

/**
 * Phone-side JavaScript is listed with the declared capabilities because an update that adds it gains network access
 * and, with `location`, the phone's location through the watchapp permissions, without declaring anything new.
 */
const val PKJS_CAPABILITY = "pkjs"

/** The locker operations git sources use; [LibPebbleGitSourceLocker] in the app, a fake in tests. */
interface GitSourceLocker {
    /** Null when the file is not a readable PBW. */
    suspend fun readPbw(path: Path): PbwFacts?

    /** Stores the PBW as a sideload without opening it on a watch; false when it is not the app [uuid] or could not be stored. */
    suspend fun sideload(path: Path, uuid: Uuid): Boolean

    suspend fun cachedPbwSha256(uuid: Uuid): String?

    fun lockerUuids(): Flow<List<Uuid>>
}

class LibPebbleGitSourceLocker(private val locker: LockerApi) : GitSourceLocker {
    override suspend fun readPbw(path: Path): PbwFacts? = withContext(Dispatchers.IO) {
        try {
            val app = PbwApp(path)
            val info = app.info
            PbwFacts(
                uuid = Uuid.parse(info.uuid),
                title = info.longName.ifBlank { info.shortName },
                version = info.versionLabel,
                capabilities = info.capabilities.toSet() + if (app.hasPKJS) setOf(PKJS_CAPABILITY) else emptySet(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun sideload(path: Path, uuid: Uuid): Boolean = locker.sideloadAppWithoutLaunch(path, uuid)

    override suspend fun cachedPbwSha256(uuid: Uuid): String? = locker.cachedPbwSha256(uuid)

    override fun lockerUuids(): Flow<List<Uuid>> = locker.getAllLockerUuids()
}

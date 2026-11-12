package com.anopticlabs.gravel.gitsource

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.network.downloadAtMost
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class GitSourceStatus { UP_TO_DATE, UPDATE_AVAILABLE, NEEDS_APPROVAL, REPLACED, NOT_INSTALLED }

data class GitSourceView(
    val source: GitSourceEntity,
    val repo: GitRepo,
    val status: GitSourceStatus,
    val error: GitSourceError?,
    val pendingCapabilities: Set<String>,
)

sealed interface AddPreview {
    data object InvalidUrl : AddPreview
    data class Failed(val error: GitSourceError) : AddPreview
    data class SeveralAssets(val repo: GitRepo, val release: GitRelease, val names: List<String>) : AddPreview
    data class Ready(val repo: GitRepo, val release: GitRelease, val asset: GitAsset, val assetPattern: String?) : AddPreview
}

/** A first install's PBW, downloaded, checked and staged, waiting for the user to confirm what it declares. */
class PreparedInstall internal constructor(
    val repo: GitRepo,
    val release: GitRelease,
    val asset: GitAsset,
    val assetPattern: String?,
    val facts: PbwFacts,
    /** The tracked source this install would replace: the same app followed from another repo or with other settings. */
    val replacesSource: GitSourceEntity?,
    val alreadyInLocker: Boolean,
    internal val sha256: String,
    internal val staged: Path,
)

sealed interface PrepareResult {
    data class Ready(val prepared: PreparedInstall) : PrepareResult
    data class Failed(val error: GitSourceError) : PrepareResult
}

sealed interface InstallResult {
    data object Installed : InstallResult
    data class NeedsApproval(val newCapabilities: Set<String>) : InstallResult
    data class Failed(val error: GitSourceError) : InstallResult
}

interface GitSourceNotifier {
    fun notifyUpdate(source: GitSourceEntity, needsApproval: Boolean)
    fun cancel(source: GitSourceEntity)
}

/**
 * Installs watch apps and faces from git releases and keeps them updated.
 *
 * Trust is pinned on first use: the UUID the first install declared is the source's key, and a later release is
 * installed only when its PBW declares the same UUID. An update that declares capabilities the installed release did
 * not (see [PKJS_CAPABILITY]) is installed only once the user approved exactly those. A download must have the size
 * the host's API listed, and GitHub's digest when it lists one.
 *
 * A source whose locker copy is no longer the one it installed ([GitSourceStatus.REPLACED]: sideloaded by hand or
 * replaced from a store) or that was removed from the locker is never installed in the background; the user decides.
 */
class GitSourceUpdater(
    private val dao: GitSourceDao,
    private val client: GitReleaseClient,
    private val httpClient: HttpClient,
    private val locker: GitSourceLocker,
    private val notifier: GitSourceNotifier,
    private val stagingDir: Path,
    private val clock: Clock = Clock.System,
    private val downloadLimit: Duration = 60.seconds,
) {
    private val logger = Logger.withTag("GitSourceUpdater")

    // Checks, installs and edits read a row and write it back; one at a time keeps a background check from
    // overwriting what a tap just recorded.
    private val oneAtATime = Mutex()

    // The locker copy can change without the row or the locker's UUID list changing (a sideload of the same app);
    // screens bump this when they open.
    private val refreshes = MutableStateFlow(0)

    fun refresh() {
        refreshes.value++
    }

    fun sources(): Flow<List<GitSourceView>> =
        combine(dao.getAllFlow(), locker.lockerUuids(), refreshes) { rows, uuids, _ -> rows to uuids.toSet() }
            .map { (rows, uuids) -> rows.mapNotNull { view(it, uuids) } }

    fun source(uuid: Uuid): Flow<GitSourceView?> =
        combine(dao.getFlow(uuid.toString()), locker.lockerUuids(), refreshes) { row, uuids, _ -> row to uuids.toSet() }
            .map { (row, uuids) -> row?.let { view(it, uuids) } }

    private suspend fun view(row: GitSourceEntity, lockerUuids: Set<Uuid>): GitSourceView? {
        val repo = row.toGitRepo() ?: return null
        return GitSourceView(
            source = row,
            repo = repo,
            status = status(row, lockerUuids),
            error = row.lastError?.let { name -> GitSourceError.entries.firstOrNull { it.name == name } },
            pendingCapabilities = row.pendingCapabilities.toCapabilities(),
        )
    }

    private suspend fun status(row: GitSourceEntity, lockerUuids: Set<Uuid>): GitSourceStatus {
        val uuid = Uuid.parse(row.uuid)
        return when {
            uuid !in lockerUuids -> GitSourceStatus.NOT_INSTALLED
            locker.cachedPbwSha256(uuid) != row.installedSha256 -> GitSourceStatus.REPLACED
            row.pendingCapabilities != null -> GitSourceStatus.NEEDS_APPROVAL
            row.hasUpdate() -> GitSourceStatus.UPDATE_AVAILABLE
            else -> GitSourceStatus.UP_TO_DATE
        }
    }

    /** Reads [input] as a repo address and finds the release and PBW an add would install. */
    suspend fun preview(input: String, includePrereleases: Boolean, assetPattern: String?): AddPreview {
        val repo = parseGitRepoUrl(input) ?: return AddPreview.InvalidUrl
        if (repo.host == GitHost.FORGEJO && !client.isForge(repo)) return AddPreview.Failed(GitSourceError.NOT_A_FORGE)
        val releases = when (val fetched = client.releases(repo, etag = null)) {
            is ReleaseFetch.Success -> fetched.releases
            is ReleaseFetch.Failure -> return AddPreview.Failed(fetched.error)
            ReleaseFetch.NotModified -> return AddPreview.Failed(GitSourceError.MALFORMED)
        }
        return when (val selection = selectRelease(repo, releases, includePrereleases, assetPattern)) {
            is ReleaseSelection.Found -> AddPreview.Ready(repo, selection.release, selection.asset, assetPattern)
            ReleaseSelection.NoRelease -> AddPreview.Failed(GitSourceError.NO_RELEASE)
            is ReleaseSelection.NoAsset -> AddPreview.Failed(GitSourceError.NO_ASSET)
            is ReleaseSelection.SeveralAssets -> AddPreview.SeveralAssets(repo, selection.release, selection.names)
        }
    }

    suspend fun prepare(ready: AddPreview.Ready): PrepareResult {
        val staged = when (val download = downloadAndRead(ready.asset)) {
            is Download.Failed -> return PrepareResult.Failed(download.error)
            is Download.Staged -> download
        }
        val uuid = staged.facts.uuid
        return PrepareResult.Ready(
            PreparedInstall(
                repo = ready.repo,
                release = ready.release,
                asset = ready.asset,
                assetPattern = ready.assetPattern,
                facts = staged.facts,
                replacesSource = dao.get(uuid.toString()),
                alreadyInLocker = locker.lockerUuids().first().contains(uuid),
                sha256 = staged.sha256,
                staged = staged.path,
            ),
        )
    }

    /** Installs a [prepare]d PBW the user confirmed and starts following its repo; replaces a source the app already had. */
    suspend fun completeAdd(prepared: PreparedInstall, includePrereleases: Boolean, autoUpdate: Boolean): InstallResult =
        oneAtATime.withLock {
            try {
                if (!locker.sideload(prepared.staged, prepared.facts.uuid)) return@withLock InstallResult.Failed(GitSourceError.INSTALL_FAILED)
                val release = prepared.release
                val asset = prepared.asset
                dao.upsert(
                    GitSourceEntity(
                        uuid = prepared.facts.uuid.toString(),
                        host = prepared.repo.host.name,
                        baseUrl = prepared.repo.baseUrl,
                        owner = prepared.repo.owner,
                        repo = prepared.repo.repo,
                        appTitle = prepared.facts.title,
                        assetPattern = prepared.assetPattern,
                        includePrereleases = includePrereleases,
                        autoUpdate = autoUpdate,
                        installedTag = release.tag,
                        installedPublishedAt = release.publishedAt.toEpochMilliseconds(),
                        installedSha256 = prepared.sha256,
                        installedCapabilities = prepared.facts.capabilities.joinCapabilities(),
                        availableTag = release.tag,
                        availablePublishedAt = release.publishedAt.toEpochMilliseconds(),
                        availableAssetName = asset.name,
                        availableAssetUrl = asset.url,
                        availableAssetSize = asset.size,
                        availableAssetSha256 = asset.sha256,
                        availableNotes = release.notes,
                        lastCheckedAt = clock.now().toEpochMilliseconds(),
                    ),
                )
                InstallResult.Installed
            } finally {
                discard(prepared)
            }
        }

    fun discard(prepared: PreparedInstall) {
        deleteQuietly(prepared.staged)
    }

    /** Fetches [uuid]'s releases now and records what it found; the error, or null. */
    suspend fun check(uuid: Uuid): GitSourceError? = oneAtATime.withLock { checkLocked(uuid.toString()) }

    private suspend fun checkLocked(uuid: String): GitSourceError? {
        val row = dao.get(uuid) ?: return null
        val repo = row.toGitRepo() ?: return GitSourceError.MALFORMED
        val now = clock.now().toEpochMilliseconds()
        val selection = when (val fetched = client.releases(repo, row.etag)) {
            ReleaseFetch.NotModified -> {
                dao.upsert(row.copy(lastCheckedAt = now, lastError = row.lastError.takeIf { it in INSTALL_ERRORS }))
                return row.lastError?.let { name -> GitSourceError.entries.firstOrNull { it.name == name } }
            }
            is ReleaseFetch.Failure -> {
                dao.upsert(row.copy(lastCheckedAt = now, lastError = fetched.error.name))
                return fetched.error
            }
            is ReleaseFetch.Success -> fetched.etag to selectRelease(repo, fetched.releases, row.includePrereleases, row.assetPattern)
        }
        val (etag, found) = selection
        val error = when (found) {
            is ReleaseSelection.Found -> null
            ReleaseSelection.NoRelease -> GitSourceError.NO_RELEASE
            is ReleaseSelection.NoAsset -> GitSourceError.NO_ASSET
            is ReleaseSelection.SeveralAssets -> GitSourceError.SEVERAL_ASSETS
        }
        if (error != null || found !is ReleaseSelection.Found) {
            // No etag: an edited pattern or pre-release setting can make the same list select something.
            dao.upsert(row.copy(lastCheckedAt = now, lastError = error?.name, etag = null))
            return error
        }
        val sameRelease = found.release.tag == row.availableTag && found.asset.url == row.availableAssetUrl
        dao.upsert(
            row.copy(
                availableTag = found.release.tag,
                availablePublishedAt = found.release.publishedAt.toEpochMilliseconds(),
                availableAssetName = found.asset.name,
                availableAssetUrl = found.asset.url,
                availableAssetSize = found.asset.size,
                availableAssetSha256 = found.asset.sha256,
                availableNotes = found.release.notes,
                // An install that failed for this release keeps its error (and stays out of the background) until
                // the repo publishes another one; so does a capability question.
                pendingCapabilities = row.pendingCapabilities.takeIf { sameRelease },
                lastError = row.lastError.takeIf { sameRelease && it in INSTALL_ERRORS },
                lastCheckedAt = now,
                etag = etag,
            ),
        )
        return null
    }

    /**
     * Downloads and installs the release the last check found for [uuid]. When it declares capabilities the
     * installed release did not, it is installed only if [approvedCapabilities] holds all of them; otherwise they are
     * recorded for the user to answer.
     */
    suspend fun install(uuid: Uuid, approvedCapabilities: Set<String> = emptySet()): InstallResult =
        oneAtATime.withLock { installLocked(uuid.toString(), approvedCapabilities) }

    private suspend fun installLocked(uuid: String, approvedCapabilities: Set<String>): InstallResult {
        val row = dao.get(uuid) ?: return InstallResult.Failed(GitSourceError.NOT_FOUND)
        val asset = row.availableAsset() ?: return InstallResult.Failed(GitSourceError.NO_RELEASE)
        val staged = when (val download = downloadAndRead(asset)) {
            is Download.Failed -> {
                dao.upsert(row.copy(lastError = download.error.name))
                return InstallResult.Failed(download.error)
            }
            is Download.Staged -> download
        }
        try {
            if (staged.facts.uuid.toString() != uuid) {
                logger.w { "Release ${row.availableTag} of ${row.owner}/${row.repo} is another app (${staged.facts.uuid})" }
                dao.upsert(row.copy(lastError = GitSourceError.WRONG_APP.name))
                return InstallResult.Failed(GitSourceError.WRONG_APP)
            }
            val added = staged.facts.capabilities - row.installedCapabilities.toCapabilities()
            if (!approvedCapabilities.containsAll(added)) {
                dao.upsert(row.copy(pendingCapabilities = added.joinCapabilities(), lastError = null))
                return InstallResult.NeedsApproval(added)
            }
            if (!locker.sideload(staged.path, staged.facts.uuid)) {
                dao.upsert(row.copy(lastError = GitSourceError.INSTALL_FAILED.name))
                return InstallResult.Failed(GitSourceError.INSTALL_FAILED)
            }
            val installed = row.copy(
                appTitle = staged.facts.title,
                installedTag = row.availableTag!!,
                installedPublishedAt = row.availablePublishedAt!!,
                installedSha256 = staged.sha256,
                installedCapabilities = staged.facts.capabilities.joinCapabilities(),
                pendingCapabilities = null,
                lastError = null,
            )
            dao.upsert(installed)
            notifier.cancel(installed)
            return InstallResult.Installed
        } finally {
            deleteQuietly(staged.path)
        }
    }

    /** Saves [uuid]'s settings; the next check reads the release list again so a new pattern applies to it. */
    suspend fun setOptions(uuid: Uuid, includePrereleases: Boolean, autoUpdate: Boolean, assetPattern: String?) =
        oneAtATime.withLock {
            val row = dao.get(uuid.toString()) ?: return@withLock
            dao.upsert(row.copy(includePrereleases = includePrereleases, autoUpdate = autoUpdate, assetPattern = assetPattern, etag = null))
        }

    /** Stops following the repo; the app stays in the locker. */
    suspend fun forget(uuid: Uuid) = oneAtATime.withLock {
        dao.get(uuid.toString())?.let { notifier.cancel(it) }
        dao.delete(uuid.toString())
    }

    /**
     * The periodic sync's part: checks each source not checked within [BACKGROUND_CHECK_INTERVAL] (sooner after a
     * transient error), installs updates from sources set to auto-update, and notifies once per release for the rest.
     */
    suspend fun backgroundCheck() = oneAtATime.withLock {
        val now = clock.now()
        for (row in dao.getAll()) {
            val checkedAt = row.lastCheckedAt?.let { Instant.fromEpochMilliseconds(it) }
            val transientError = row.lastError?.let { name -> GitSourceError.entries.firstOrNull { it.name == name }?.transient } == true
            if (checkedAt == null || transientError || now - checkedAt >= BACKGROUND_CHECK_INTERVAL) {
                checkLocked(row.uuid)
            }
        }
        val lockerUuids = locker.lockerUuids().first().toSet()
        for (row in dao.getAll()) {
            var status = status(row, lockerUuids)
            if (status == GitSourceStatus.UPDATE_AVAILABLE && row.autoUpdate && (row.lastError == null || row.lastError in TRANSIENT_ERRORS)) {
                when (installLocked(row.uuid, emptySet())) {
                    InstallResult.Installed -> continue
                    is InstallResult.NeedsApproval -> status = GitSourceStatus.NEEDS_APPROVAL
                    is InstallResult.Failed -> Unit
                }
            }
            if (status != GitSourceStatus.UPDATE_AVAILABLE && status != GitSourceStatus.NEEDS_APPROVAL) continue
            val current = dao.get(row.uuid) ?: continue
            if (current.notifiedTag == current.availableTag) continue
            notifier.notifyUpdate(current, needsApproval = status == GitSourceStatus.NEEDS_APPROVAL)
            dao.upsert(current.copy(notifiedTag = current.availableTag))
        }
    }

    private sealed interface Download {
        data class Staged(val path: Path, val sha256: String, val facts: PbwFacts) : Download
        data class Failed(val error: GitSourceError) : Download
    }

    /** Nothing parses the download before its size, and its digest when the host lists one, are checked. */
    private suspend fun downloadAndRead(asset: GitAsset): Download {
        if (asset.size !in 1..MAX_GIT_PBW_BYTES) return Download.Failed(GitSourceError.CHECK_FAILED)
        val bytes = try {
            withTimeoutOrNull(downloadLimit) { httpClient.downloadAtMost(asset.url, asset.size) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Download of ${asset.url} failed" }
            null
        } ?: return Download.Failed(GitSourceError.NETWORK)
        if (bytes.size.toLong() != asset.size) return Download.Failed(GitSourceError.CHECK_FAILED)
        val sha256 = bytes.toByteString().sha256().hex()
        if (asset.sha256 != null && asset.sha256 != sha256) return Download.Failed(GitSourceError.CHECK_FAILED)
        val path = Path(stagingDir, "${Uuid.random()}.pbw")
        try {
            withContext(Dispatchers.IO) {
                SystemFileSystem.createDirectories(stagingDir)
                SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
            }
        } catch (e: CancellationException) {
            deleteQuietly(path)
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Could not stage ${asset.name}" }
            deleteQuietly(path)
            return Download.Failed(GitSourceError.INSTALL_FAILED)
        }
        val facts = locker.readPbw(path) ?: run {
            deleteQuietly(path)
            return Download.Failed(GitSourceError.CHECK_FAILED)
        }
        return Download.Staged(path, sha256, facts)
    }

    private fun deleteQuietly(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (e: Exception) {
            logger.w(e) { "Could not delete $path" }
        }
    }

    companion object {
        val BACKGROUND_CHECK_INTERVAL = 6.hours
        private val INSTALL_ERRORS = setOf(GitSourceError.CHECK_FAILED, GitSourceError.WRONG_APP, GitSourceError.INSTALL_FAILED).map { it.name }
        private val TRANSIENT_ERRORS = GitSourceError.entries.filter { it.transient }.map { it.name }
    }
}

fun GitSourceEntity.toGitRepo(): GitRepo? {
    val host = GitHost.entries.firstOrNull { it.name == host } ?: return null
    return GitRepo(host, baseUrl, owner, repo)
}

private fun GitSourceEntity.availableAsset(): GitAsset? {
    val url = availableAssetUrl ?: return null
    return GitAsset(availableAssetName ?: return null, url, availableAssetSize ?: return null, availableAssetSha256)
}

private fun GitSourceEntity.hasUpdate(): Boolean {
    val tag = availableTag ?: return false
    val publishedAt = availablePublishedAt ?: return false
    return isUpdate(installedTag, Instant.fromEpochMilliseconds(installedPublishedAt), tag, Instant.fromEpochMilliseconds(publishedAt))
}

internal fun String?.toCapabilities(): Set<String> = this?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

internal fun Set<String>.joinCapabilities(): String = sorted().joinToString("\n")


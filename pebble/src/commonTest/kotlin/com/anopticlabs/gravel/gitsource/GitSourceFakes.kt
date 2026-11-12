package com.anopticlabs.gravel.gitsource

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import okio.ByteString.Companion.toByteString
import kotlin.uuid.Uuid

internal fun sha(bytes: ByteArray) = bytes.toByteString().sha256().hex()

internal class FakeDao : GitSourceDao {
    val rows = MutableStateFlow<Map<String, GitSourceEntity>>(emptyMap())
    override fun getAllFlow(): Flow<List<GitSourceEntity>> = rows.map { it.values.toList() }
    override suspend fun getAll() = rows.value.values.toList()
    override fun getFlow(uuid: String): Flow<GitSourceEntity?> = rows.map { it[uuid] }
    override suspend fun get(uuid: String) = rows.value[uuid]
    override suspend fun upsert(source: GitSourceEntity) { rows.value += source.uuid to source }
    override suspend fun delete(uuid: String) { rows.value -= uuid }
}

/** Reads a staged file's bytes as the key into [facts], which stands in for `appinfo.json`. */
internal class FakeLocker(private val facts: Map<String, PbwFacts>) : GitSourceLocker {
    val installed = MutableStateFlow<Map<Uuid, String>>(emptyMap())
    val sideloads = mutableListOf<String>()
    var stagedPaths = mutableListOf<Path>()
    override suspend fun readPbw(path: Path): PbwFacts? {
        stagedPaths += path
        return facts[SystemFileSystem.source(path).buffered().use { it.readByteArray() }.decodeToString()]
    }
    override suspend fun sideload(path: Path, uuid: Uuid): Boolean {
        val bytes = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        if (facts[bytes.decodeToString()]?.uuid != uuid) return false
        sideloads += bytes.decodeToString()
        installed.value += uuid to sha(bytes)
        return true
    }
    override suspend fun cachedPbwSha256(uuid: Uuid) = installed.value[uuid]
    override fun lockerUuids(): Flow<List<Uuid>> = installed.map { it.keys.toList() }
}

internal class FakeNotifier : GitSourceNotifier {
    val posted = mutableListOf<Pair<String, Boolean>>()
    val canceled = mutableListOf<String>()
    override fun notifyUpdate(source: GitSourceEntity, needsApproval: Boolean) { posted += source.availableTag!! to needsApproval }
    override fun cancel(source: GitSourceEntity) { canceled += source.uuid }
}


package com.anopticlabs.gravel.gitsource

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A watch app or face kept up to date from a git host's releases, keyed by the UUID its first install
 * from that repo declared; later releases are installed only under the same UUID.
 *
 * Times are epoch milliseconds. Capability sets are stored newline-joined, as `appinfo.json` lists
 * them. The `available*` columns describe the newest release the last check selected, which may be
 * the installed one.
 */
@Entity
data class GitSourceEntity(
    @PrimaryKey val uuid: String,
    val host: String,
    val baseUrl: String,
    val owner: String,
    val repo: String,
    val appTitle: String,
    val assetPattern: String? = null,
    val includePrereleases: Boolean = false,
    val autoUpdate: Boolean = false,
    val installedTag: String,
    val installedPublishedAt: Long,
    val installedSha256: String,
    val installedCapabilities: String,
    val availableTag: String? = null,
    val availablePublishedAt: Long? = null,
    val availableAssetName: String? = null,
    val availableAssetUrl: String? = null,
    val availableAssetSize: Long? = null,
    val availableAssetSha256: String? = null,
    val availableNotes: String? = null,
    /** Capabilities the available release adds over the installed one, newline-joined; null until an install attempt finds some. */
    val pendingCapabilities: String? = null,
    val lastCheckedAt: Long? = null,
    val lastError: String? = null,
    val etag: String? = null,
    /** The available tag a notification was last posted for, so each release notifies once. */
    val notifiedTag: String? = null,
)

@Dao
interface GitSourceDao {
    @Query("SELECT * FROM GitSourceEntity ORDER BY appTitle COLLATE NOCASE")
    fun getAllFlow(): Flow<List<GitSourceEntity>>

    @Query("SELECT * FROM GitSourceEntity")
    suspend fun getAll(): List<GitSourceEntity>

    @Query("SELECT * FROM GitSourceEntity WHERE uuid = :uuid LIMIT 1")
    fun getFlow(uuid: String): Flow<GitSourceEntity?>

    @Query("SELECT * FROM GitSourceEntity WHERE uuid = :uuid LIMIT 1")
    suspend fun get(uuid: String): GitSourceEntity?

    @Upsert
    suspend fun upsert(source: GitSourceEntity)

    @Query("DELETE FROM GitSourceEntity WHERE uuid = :uuid")
    suspend fun delete(uuid: String)
}

package com.anopticlabs.gravel.backup

import android.content.Context
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.backup.BackupGate
import com.anopticlabs.gravel.notebook.backup.BackupProblem
import com.anopticlabs.gravel.notebook.backup.InvalidBackupException
import com.anopticlabs.gravel.notebook.backup.NotebookBackup
import com.anopticlabs.gravel.notebook.backup.SealedFile
import com.anopticlabs.gravel.notebook.routers.RouterTrustStore
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.RoutingExport
import coredevices.database.CoreDatabase
import io.rebble.libpebblecommon.connection.LibPebble3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** What an export wrote. [notebookSkipped] is why the Notebook is not in it, when it is not. */
data class ExportReport(val manifest: AppBackupManifest, val notebookSkipped: String?)

/** What an import restored; the app restarts after one, so the restored preferences take effect. */
data class ImportReport(
    val manifest: AppBackupManifest,
    val rowsWritten: Int,
    val rowsRefused: Int,
    val appsRestored: Int,
    val appsNotRestored: Int,
    val notebookRestored: Boolean,
    val problems: List<String>,
)

/**
 * The whole app's settings and data as one [AppBackupFormat] file: the user's preferences, each
 * watchapp's PebbleKit JS storage, the locker (with sideloaded apps' PBWs), notification, contact,
 * calendar, watch, health and appstore configuration, health history, the Notebook (as its own
 * archive, sealed with the same password) and its text routing. Sign-in tokens and keys held in the
 * Android Keystore stay behind: they cannot leave the phone that made them.
 */
class AppBackup(
    private val context: Context,
    private val libPebble: LibPebble3,
    private val coreDatabase: CoreDatabase,
    private val notebookBackup: NotebookBackup,
    private val notebookGate: BackupGate,
    private val routingStore: RoutingConfigStore,
    private val trustStore: RouterTrustStore,
    private val notebookDrain: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppBackupOperations {
    private val logger = Logger.withTag("AppBackup")
    private val coreRows by lazy { TableRows(coreDatabase) }

    /** Writes the backup to [out], which this closes. The slow steps are the two key derivations. */
    override suspend fun export(out: OutputStream, password: CharArray): ExportReport = withContext(Dispatchers.IO) {
        var notebookSkipped: String? = null
        val notebook = try {
            notebookGate.withLock { notebookBackup.create(password) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Notebook left out of the app backup" }
            notebookSkipped = e.message ?: e::class.simpleName
            null
        }
        try {
            ZipOutputStream(SealedFile.create(out, AppBackupFormat.MAGIC, password)).use { zip ->
                zip.putJson(AppBackupFormat.prefsEntry(DEFAULT_PREFS), PrefValues.encode(defaultPrefs().all.filterKeys { it in AppBackupFormat.DEFAULT_PREF_KEYS }))
                zip.putJson(
                    AppBackupFormat.prefsEntry(AppBackupFormat.NOTEBOOK_PREFS_FILE),
                    PrefValues.encode(prefs(AppBackupFormat.NOTEBOOK_PREFS_FILE).all.filterKeys { it in AppBackupFormat.NOTEBOOK_PREF_KEYS }),
                )
                for (appId in pkjsAppIds()) {
                    zip.putJson(AppBackupFormat.pkjsEntry(appId), PrefValues.encode(prefs(pkjsPrefsName(appId)).all))
                }
                routingJson()?.let { zip.putBytes(AppBackupFormat.ROUTING, it.encodeToByteArray()) }

                val tables = LinkedHashMap<String, Int>()
                val sideloaded = mutableListOf<String>()
                // The PBWs go before the tables, so an import can sideload them before the locker rows replace their entries.
                val lockerRows = mutableListOf<JsonObject>()
                libPebble.backupRows.export("LockerEntryEntity") { lockerRows += it }
                for (row in lockerRows) if (row.long("sideloaded") == 1L) row.string("id")?.let(sideloaded::add)
                val missing = mutableListOf<String>()
                for (id in sideloaded) {
                    val pbw = libPebble.cachedPbwPath(Uuid.parse(id))
                    if (pbw == null) {
                        missing += id
                        continue
                    }
                    zip.putNextEntry(ZipEntry(AppBackupFormat.pbwEntry(id)))
                    File(pbw.toString()).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                for (table in AppBackupFormat.LIBPEBBLE_TABLES) {
                    tables.putRows(zip, AppBackupFormat.LIBPEBBLE_DB, table, libPebble.backupRows)
                }
                for (table in AppBackupFormat.CORE_TABLES) {
                    tables.putRows(zip, AppBackupFormat.CORE_DB, table, coreRows)
                }
                if (notebook != null) {
                    zip.putNextEntry(ZipEntry(AppBackupFormat.NOTEBOOK))
                    notebook.staged.openRead().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val manifest = AppBackupManifest(
                    version = AppBackupFormat.VERSION,
                    createdAtMs = clock(),
                    appVersion = appVersion(),
                    tables = tables,
                    missingPbws = missing,
                    notebook = notebook != null,
                )
                zip.putBytes(AppBackupFormat.MANIFEST, AppBackupFormat.json.encodeToString(AppBackupManifest.serializer(), manifest).encodeToByteArray())
                ExportReport(manifest, notebookSkipped)
            }
        } finally {
            notebook?.close()
        }
    }

    /** Reads [input] to its end and returns its manifest, applying nothing; throws [InvalidBackupException] for a file that does not check out. */
    override suspend fun check(input: InputStream, password: CharArray): AppBackupManifest = withContext(Dispatchers.IO) {
        val header = SealedFile.readHeader(input, AppBackupFormat.MAGIC)
        val key = SealedFile.deriveKey(header, password)
        try {
            verifyBackup(SealedFile.open(input, header, key))
        } finally {
            key.wipe()
        }
    }

    /**
     * Copies [input] to a staging file, checks it through to its authenticated end, then restores
     * it. Rows are upserted and nothing is deleted. The preferences written take effect once the
     * app restarts, which the caller does next.
     */
    override suspend fun import(input: InputStream, password: CharArray): ImportReport = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, STAGED_IMPORT)
        try {
            staged.outputStream().use { input.copyTo(it) }
            val header = staged.inputStream().use { SealedFile.readHeader(it, AppBackupFormat.MAGIC) }
            val key = SealedFile.deriveKey(header, password)
            try {
                val manifest = staged.inputStream().use { file ->
                    SealedFile.readHeader(file, AppBackupFormat.MAGIC)
                    verifyBackup(SealedFile.open(file, header, key))
                }
                staged.inputStream().use { file ->
                    SealedFile.readHeader(file, AppBackupFormat.MAGIC)
                    apply(SealedFile.open(file, header, key), manifest, password)
                }
            } finally {
                key.wipe()
            }
        } finally {
            staged.delete()
        }
    }


    private suspend fun apply(plain: InputStream, manifest: AppBackupManifest, password: CharArray): ImportReport {
        var rowsWritten = 0
        var rowsRefused = 0
        var appsRestored = 0
        var appsNotRestored = manifest.missingPbws.size
        var notebookRestored = false
        val problems = mutableListOf<String>()
        val zip = ZipInputStream(plain)
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            when {
                name == AppBackupFormat.prefsEntry(DEFAULT_PREFS) ->
                    writePrefs(defaultPrefs(), zip.readJsonObject(), AppBackupFormat.DEFAULT_PREF_KEYS)
                name == AppBackupFormat.prefsEntry(AppBackupFormat.NOTEBOOK_PREFS_FILE) ->
                    writePrefs(prefs(AppBackupFormat.NOTEBOOK_PREFS_FILE), zip.readJsonObject(), AppBackupFormat.NOTEBOOK_PREF_KEYS)
                name.startsWith(AppBackupFormat.PKJS_DIR) -> {
                    val appId = appIdIn(name, AppBackupFormat.PKJS_DIR, ".json") ?: continue
                    writePrefs(prefs(pkjsPrefsName(appId)), zip.readJsonObject(), keys = null)
                }
                name == AppBackupFormat.ROUTING -> try {
                    val parsed = RoutingExport.decode(zip.readCapped(RoutingExport.MAX_CHARS.toLong() * 4).decodeToString())
                    routingStore.write(parsed.routing)
                    if (!trustStore.replace(parsed.trust)) problems += "Router trust could not be saved."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problems += "Text routing was not restored: ${e.message}"
                }
                name.startsWith(AppBackupFormat.PBW_DIR) -> {
                    val appId = appIdIn(name, AppBackupFormat.PBW_DIR, ".pbw") ?: continue
                    val pbw = File(context.cacheDir, "app-backup-$appId.pbw")
                    try {
                        pbw.outputStream().use { zip.copyCapped(it, AppBackupFormat.MAX_PBW_BYTES) }
                        if (libPebble.sideloadAppWithoutLaunch(Path(pbw.absolutePath), Uuid.parse(appId))) appsRestored++ else appsNotRestored++
                    } finally {
                        pbw.delete()
                    }
                }
                name.startsWith(AppBackupFormat.TABLES_DIR) -> {
                    val (database, table) = tableIn(name) ?: continue
                    val rows = when (database) {
                        AppBackupFormat.LIBPEBBLE_DB -> libPebble.backupRows
                        AppBackupFormat.CORE_DB -> coreRows
                        else -> continue
                    }
                    val result = rows.import(table, zip.jsonLines())
                    if (result == null) {
                        problems += "$table is not in this version of Gravel."
                    } else {
                        rowsWritten += result.written
                        rowsRefused += result.refused
                    }
                }
                name == AppBackupFormat.NOTEBOOK -> notebookRestored = restoreNotebook(zip, password, problems)
            }
        }
        return ImportReport(manifest, rowsWritten, rowsRefused, appsRestored, appsNotRestored, notebookRestored, problems)
    }

    private suspend fun restoreNotebook(zip: ZipInputStream, password: CharArray, problems: MutableList<String>): Boolean = try {
        notebookBackup.stage(NonClosing(zip)).use { archive ->
            val key = notebookBackup.unlock(archive, password)
            try {
                val report = notebookGate.withLock { notebookBackup.restore(archive, key) }
                if (report.capturesAdded > 0) notebookDrain()
            } finally {
                key.wipe()
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "Notebook not restored from the app backup" }
        problems += "The Notebook was not restored: ${e.message}"
        false
    }

    private fun routingJson(): String? = try {
        RoutingExport.encode(routingStore.read(), trustStore.read())
    } catch (e: Exception) {
        // No routing saved yet, or none readable: there is nothing to carry.
        null
    }

    private fun writePrefs(target: android.content.SharedPreferences, json: JsonObject, keys: Set<String>?) {
        val values = PrefValues.decode(json).filterKeys { keys == null || it in keys }
        val editor = target.edit()
        if (keys == null) editor.clear()
        for ((key, value) in values) {
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        // commit: the app restarts right after an import, before an apply() would be sure to land.
        if (!editor.commit()) throw IOException("preferences not saved")
    }

    private fun defaultPrefs() = prefs("${context.packageName}_preferences")

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun pkjsPrefsName(appId: String) = "${context.packageName}_pkjs_$appId"

    /** The watchapps with PebbleKit JS storage, by the UUID their preferences file is named after. */
    private fun pkjsAppIds(): List<String> {
        val prefix = "${context.packageName}_pkjs_"
        return File(context.applicationInfo.dataDir, "shared_prefs").list().orEmpty()
            .filter { it.startsWith(prefix) && it.endsWith(".xml") }
            .map { it.removePrefix(prefix).removeSuffix(".xml") }
            .filter { runCatching { Uuid.parse(it) }.isSuccess }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private suspend fun MutableMap<String, Int>.putRows(zip: ZipOutputStream, database: String, table: String, rows: TableRows) {
        val entry = AppBackupFormat.tableEntry(database, table)
        zip.putNextEntry(ZipEntry(entry))
        val count = rows.export(table) { row ->
            zip.write(row.toString().encodeToByteArray())
            zip.write('\n'.code)
        }
        zip.closeEntry()
        if (count != null) put(entry, count)
    }

    private companion object {
        const val DEFAULT_PREFS = "default"
        const val STAGED_IMPORT = "app-backup-import.gbak"
    }

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.putJson(name: String, json: JsonObject) = putBytes(name, json.toString().encodeToByteArray())
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull


package com.anopticlabs.gravel.backup

import com.anopticlabs.gravel.notebook.backup.BackupProblem
import com.anopticlabs.gravel.notebook.backup.InvalidBackupException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

/**
 * Reads the backup's plaintext [plain] through to the sealed stream's end and returns its
 * manifest. The zip's central directory can end before the sealed stream does, and only the
 * stream's own end proves nothing was cut off, so the rest is drained before anything counts.
 */
internal fun verifyBackup(plain: InputStream): AppBackupManifest {
    var manifest: AppBackupManifest? = null
    val zip = ZipInputStream(plain)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == AppBackupFormat.MANIFEST) {
            manifest = try {
                AppBackupFormat.json.decodeFromString(AppBackupManifest.serializer(), zip.readCapped(AppBackupFormat.MAX_JSON_ENTRY_BYTES).decodeToString())
            } catch (e: IllegalArgumentException) {
                throw damaged("its contents list does not read")
            }
        } else {
            zip.skip(Long.MAX_VALUE)
        }
    }
    plain.skip(Long.MAX_VALUE)
    if (plain.read() != -1) throw damaged("data follows its end")
    val found = manifest ?: throw damaged("it has no contents list")
    if (found.version > AppBackupFormat.VERSION) throw InvalidBackupException(BackupProblem.NEWER_FORMAT, "backup from a newer version of Gravel")
    return found
}

/** A pkjs or pbw entry's app id as written (a PebbleKit JS storage file is named after the id exactly as the app declares it), or null for a name that is not one. */
internal fun appIdIn(name: String, dir: String, suffix: String): String? {
    if (!name.startsWith(dir) || !name.endsWith(suffix)) return null
    val id = name.substring(dir.length, name.length - suffix.length)
    return try {
        Uuid.parse(id)
        id
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** The database and table of a table entry, or null for a name that is not one of the tables a backup holds. */
internal fun tableIn(name: String): Pair<String, String>? {
    if (!name.startsWith(AppBackupFormat.TABLES_DIR) || !name.endsWith(".jsonl")) return null
    val parts = name.removePrefix(AppBackupFormat.TABLES_DIR).removeSuffix(".jsonl").split('/')
    if (parts.size != 2) return null
    val (database, table) = parts
    val known = when (database) {
        AppBackupFormat.LIBPEBBLE_DB -> AppBackupFormat.LIBPEBBLE_TABLES
        AppBackupFormat.CORE_DB -> AppBackupFormat.CORE_TABLES
        else -> return null
    }
    return (database to table).takeIf { table in known }
}

internal fun InputStream.readJsonObject(): JsonObject = try {
    AppBackupFormat.json.parseToJsonElement(readCapped(AppBackupFormat.MAX_JSON_ENTRY_BYTES).decodeToString()).jsonObject
} catch (e: IllegalArgumentException) {
    throw damaged("an entry does not read")
}

/** [this] entry's lines as JSON objects, read as the consumer takes them; the stream is not closed. */
internal fun InputStream.jsonLines(maxLineBytes: Int = MAX_LINE_BYTES): Sequence<JsonObject> = sequence {
    val input = BufferedInputStream(NonClosing(this@jsonLines))
    val line = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b == -1 || b == '\n'.code) {
            if (line.size() > 0) {
                val parsed = try {
                    AppBackupFormat.json.parseToJsonElement(line.toString(Charsets.UTF_8.name())).jsonObject
                } catch (e: IllegalArgumentException) {
                    throw damaged("a table row does not read")
                }
                yield(parsed)
                line.reset()
            }
            if (b == -1) break
        } else {
            if (line.size() >= maxLineBytes) throw damaged("a table row is over its size cap")
            line.write(b)
        }
    }
}

internal fun InputStream.readCapped(cap: Long): ByteArray {
    val out = ByteArrayOutputStream()
    copyCapped(out, cap)
    return out.toByteArray()
}

internal fun InputStream.copyCapped(out: OutputStream, cap: Long) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > cap) throw damaged("an entry is over its size cap")
        out.write(buffer, 0, n)
    }
}

/** Keeps a consumer that closes its input from closing the zip around the current entry. */
internal class NonClosing(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

internal fun damaged(detail: String) = InvalidBackupException(BackupProblem.DAMAGED, "backup file is damaged: $detail")

private const val MAX_LINE_BYTES = 1024 * 1024

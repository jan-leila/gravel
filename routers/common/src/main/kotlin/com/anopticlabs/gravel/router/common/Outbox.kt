package com.anopticlabs.gravel.router.common

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** A delivery set aside after a refusal or too many tries: what it was, why, and when it was set aside. */
data class NotSent(val file: File, val delivery: Delivery, val reason: String, val atMillis: Long)

/**
 * The deliveries waiting to be sent, one file each under the app's private files, named by the
 * capture id, so a repeated delivery of one recording replaces the first instead of doubling it.
 * A delivery that is not going to be sent as things stand is kept under [NOT_SENT_DIR] with its
 * reason, for the settings screen to show, until the user tries it again or discards it: the text
 * is the only copy there is, as Gravel keeps no transcript once a router took the delivery.
 */
class Outbox(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val notSentDir = File(dir, NOT_SENT_DIR)

    /** Writes the delivery, whole or not at all. */
    @Throws(IOException::class)
    fun add(delivery: Delivery) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("outbox directory not created")
        val file = File(dir, fileName(delivery.captureId))
        val staging = File(dir, "${file.name}.tmp")
        FileOutputStream(staging).use { out ->
            delivery.write(out)
            out.fd.sync()
        }
        if (!staging.renameTo(file)) {
            staging.delete()
            throw IOException("outbox file not renamed")
        }
    }

    /** Every waiting delivery with the file it is in, oldest first; a file that is not a delivery is removed. */
    fun list(): List<Pair<File, Delivery>> = deliveries(dir)

    fun remove(file: File) {
        file.delete()
    }

    /** Takes [file] out of the line with [reason]; past [MAX_NOT_SENT] set aside, the oldest go. */
    fun keepNotSent(file: File, reason: String) {
        if (!notSentDir.isDirectory && !notSentDir.mkdirs()) return
        val kept = File(notSentDir, file.name)
        if (!file.renameTo(kept)) return
        File(notSentDir, kept.name + REASON_SUFFIX).writeText(reason.take(RouterContract.MAX_REFUSAL_CHARS))
        listNotSent().drop(MAX_NOT_SENT).forEach(::discard)
    }

    /** What was set aside, newest first. */
    fun listNotSent(): List<NotSent> = deliveries(notSentDir)
        .map { (file, delivery) ->
            val reason = File(notSentDir, file.name + REASON_SUFFIX).takeIf { it.isFile }?.readText().orEmpty()
            NotSent(file, delivery, reason, file.lastModified())
        }
        .sortedByDescending { it.atMillis }

    /** Puts the delivery back in line, to be sent on the next run. */
    fun tryAgain(notSent: NotSent) {
        File(notSentDir, notSent.file.name + REASON_SUFFIX).delete()
        if (!notSent.file.renameTo(File(dir, notSent.file.name))) notSent.file.delete()
    }

    fun discard(notSent: NotSent) {
        File(notSentDir, notSent.file.name + REASON_SUFFIX).delete()
        notSent.file.delete()
    }

    private fun deliveries(from: File): List<Pair<File, Delivery>> = from.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(EXTENSION) }
        .sortedBy { it.lastModified() }
        .mapNotNull { file ->
            val delivery = try {
                file.inputStream().use { Delivery.read(it) }
            } catch (e: IOException) {
                null
            }
            if (delivery == null) {
                file.delete()
                null
            } else {
                file to delivery
            }
        }

    companion object {
        const val DIR_NAME = "outbox"
        const val NOT_SENT_DIR = "not_sent"
        const val MAX_NOT_SENT = 50
        private const val EXTENSION = ".delivery"
        private const val REASON_SUFFIX = ".reason"
        private val SAFE = Regex("[A-Za-z0-9-]{1,64}")

        /** The id as a file name: itself when it is plain, else its digest, so another app's id cannot name a path. */
        fun fileName(captureId: String): String {
            val stem = if (SAFE.matches(captureId)) captureId else MessageDigest.getInstance("SHA-256").digest(captureId.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            return stem + EXTENSION
        }
    }
}

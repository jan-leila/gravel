package com.anopticlabs.gravel.router.common

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The deliveries waiting to be sent, one file each under the app's private files, named by the
 * capture id, so a repeated delivery of one recording replaces the first instead of doubling it.
 */
class Outbox(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

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
    fun list(): List<Pair<File, Delivery>> = dir.listFiles().orEmpty()
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

    fun remove(file: File) {
        file.delete()
    }

    companion object {
        const val DIR_NAME = "outbox"
        private const val EXTENSION = ".delivery"
        private val SAFE = Regex("[A-Za-z0-9-]{1,64}")

        /** The id as a file name: itself when it is plain, else its digest, so another app's id cannot name a path. */
        fun fileName(captureId: String): String {
            val stem = if (SAFE.matches(captureId)) captureId else MessageDigest.getInstance("SHA-256").digest(captureId.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            return stem + EXTENSION
        }
    }
}

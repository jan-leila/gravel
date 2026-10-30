package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import com.anopticlabs.gravel.notebook.storage.EncryptedFiles
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.PersistedFormatException
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class RoutingConfigStoreTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val store = RoutingConfigStore(root, crypto)
    private val file = File(root, RoutingConfigStore.FILE_NAME)
    private val split = RoutingConfig(
        entry = RouterRef.gravel("split"),
        routers = listOf(
            RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", RouterRef.gravel("reminders"))), otherwise = RouterRef.gravel("notes")),
            RouterConfig.Note("notes", "Notes"),
            RouterConfig.Reminder("reminders", "Reminders"),
        ),
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun absentReadsAsTheDefaultAndTheStateIsUnknownUntilLoaded() = runBlocking<Unit> {
        assertNull(store.state.value)
        assertEquals(RoutingConfig.DEFAULT, store.read())
        assertEquals(RoutingStoreState.Loaded(RoutingConfig.DEFAULT), store.load())
        assertEquals(RoutingStoreState.Loaded(RoutingConfig.DEFAULT), store.state.value)
    }

    @Test
    fun aWrittenConfigReadsBackAndAnInvalidOneIsRefusedWithNothingWritten() = runBlocking<Unit> {
        store.write(split)
        assertEquals(split, store.read())
        assertEquals(RoutingStoreState.Loaded(split), store.state.value)
        assertEquals(split, RoutingConfigStore(root, crypto).read(), "another instance reads the file")
        assertFailsWith<IllegalArgumentException> { store.write(split.copy(entry = RouterRef.gravel("gone"))) }
        assertEquals(split, store.read())
    }

    @Test
    fun aDamagedFileIsUnreadableNeverTheDefaultAndResetStartsOver() = runBlocking<Unit> {
        store.write(split)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertFailsWith<CorruptFileException> { store.read() }
        assertEquals(RoutingStoreState.Unreadable, store.load())
        store.reset()
        assertEquals(RoutingConfig.DEFAULT, store.read())
        assertEquals(RoutingStoreState.Loaded(RoutingConfig.DEFAULT), store.load())
    }

    @Test
    fun aConfigThisBuildRefusesIsUnreadable() = runBlocking<Unit> {
        val newer = """{"v":2,"entry":{"id":"notebook"},"routers":[{"type":"note","id":"notebook","name":"Notebook"}]}"""
        EncryptedFiles(root, crypto).write(file, newer.encodeToByteArray())
        assertFailsWith<PersistedFormatException> { store.read() }
        assertEquals(RoutingStoreState.Unreadable, store.load())
    }
}

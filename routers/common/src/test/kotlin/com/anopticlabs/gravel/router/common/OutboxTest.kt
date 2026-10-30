package com.anopticlabs.gravel.router.common

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxTest {

    private val dir = Files.createTempDirectory("outbox").toFile()
    private val outbox = Outbox(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aPlainIdNamesItsFileAndAnyOtherIsDigested() {
        assertEquals("c1-2.delivery", Outbox.fileName("c1-2"))
        assertEquals("d6b96a97d147daaae49eb87a5ca7bfbc280e79551016df8aa5edc5920d21c274.delivery", Outbox.fileName("../x"))
        assertEquals(Outbox.fileName("a".repeat(65)), Outbox.fileName("a".repeat(65)))
        assertFalse(Outbox.fileName("a".repeat(65)).startsWith("aaa"))
    }

    @Test
    fun deliveriesWaitOldestFirstAndARepeatReplacesTheFirst() {
        val first = Delivery("c1", "one", null, null)
        outbox.add(first)
        Thread.sleep(5)
        outbox.add(Delivery("c2", "two", null, null))
        outbox.add(first.copy(text = "one again"))
        val listed = outbox.list()
        assertEquals(listOf("c1", "c2"), listed.map { it.second.captureId }.sorted())
        assertEquals("one again", listed.first { it.second.captureId == "c1" }.second.text)
        assertEquals(2, dir.listFiles()!!.size, "no staging file is left behind")
        outbox.remove(listed.first().first)
        assertEquals(1, outbox.list().size)
    }

    @Test
    fun aFileThatIsNotADeliveryIsRemovedAndNothingElseIsListed() {
        File(dir, "junk.delivery").apply { parentFile!!.mkdirs(); writeText("text=only") }
        File(dir, "note.txt").writeText("capture_id=x\ntext=y")
        assertTrue(outbox.list().isEmpty())
        assertFalse(File(dir, "junk.delivery").exists())
        assertTrue(File(dir, "note.txt").exists())
    }
}

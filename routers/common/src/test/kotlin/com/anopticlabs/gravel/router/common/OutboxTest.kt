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
    fun oneRecordingForTwoListedRoutersIsTwoDeliveries() {
        outbox.add(Delivery("c1", "one", null, null))
        outbox.add(Delivery("c1", "one", null, null, "slack-a"))
        outbox.add(Delivery("c1", "one", null, null, "slack-b"))
        outbox.add(Delivery("c1", "one again", null, null, "slack-a"))
        assertEquals(listOf(null, "slack-a", "slack-b"), outbox.list().map { it.second.routerId }.sortedBy { it.orEmpty() })
        assertEquals("one again", outbox.list().first { it.second.routerId == "slack-a" }.second.text)
    }

    @Test
    fun aDeliverySetAsideLeavesTheLineWithItsReasonUntilItIsTriedAgainOrDiscarded() {
        outbox.add(Delivery("c1", "one", null, null))
        outbox.add(Delivery("c2", "two", null, null))
        val first = outbox.list().first { it.second.captureId == "c1" }.first
        outbox.keepNotSent(first, "Slack refused the webhook (HTTP 404)")
        assertEquals(listOf("c2"), outbox.list().map { it.second.captureId })
        val aside = outbox.listNotSent().single()
        assertEquals("one", aside.delivery.text)
        assertEquals("Slack refused the webhook (HTTP 404)", aside.reason)
        outbox.tryAgain(aside)
        assertEquals(listOf("c1", "c2"), outbox.list().map { it.second.captureId }.sorted())
        assertTrue(outbox.listNotSent().isEmpty())
        outbox.keepNotSent(outbox.list().first { it.second.captureId == "c2" }.first, "gone")
        outbox.discard(outbox.listNotSent().single())
        assertTrue(outbox.listNotSent().isEmpty())
        assertEquals(listOf("c1"), outbox.list().map { it.second.captureId })
        assertTrue(File(dir, Outbox.NOT_SENT_DIR).listFiles()!!.isEmpty(), "no reason file is left behind")
    }

    @Test
    fun onlyTheNewestSetAsideDeliveriesAreKept() {
        repeat(Outbox.MAX_NOT_SENT + 2) { n ->
            outbox.add(Delivery("c$n", "text $n", null, null))
            val file = outbox.list().single().first
            file.setLastModified(1_000_000L + n * 1_000)
            outbox.keepNotSent(file, "reason $n")
        }
        val kept = outbox.listNotSent()
        assertEquals(Outbox.MAX_NOT_SENT, kept.size)
        assertEquals("c${Outbox.MAX_NOT_SENT + 1}", kept.first().delivery.captureId, "newest first")
        assertFalse(kept.any { it.delivery.captureId == "c0" || it.delivery.captureId == "c1" }, "the oldest went")
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

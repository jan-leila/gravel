package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class AppRouterDeliveryTest {

    private val a = "a".repeat(64)
    private val old = "c".repeat(64)
    private val tasks = RouterConfig.App("tasks", "Tasks", "com.example.tasks", "com.example.tasks.Router", a)
    private val text = RoutedText("c1", "2026-09-22T17:00:00+02:00", "buy milk")

    private class FakeInstalled : InstalledRouters {
        val routers = mutableListOf<InstalledRouter>()
        val lineage = mutableMapOf<String, Set<String>>()
        override fun discover() = routers.toList()
        override fun resolve(packageName: String, receiverClass: String) = routers.firstOrNull { it.packageName == packageName && it.receiverClass == receiverClass }
        override fun signedBy(packageName: String, sha256: String) = sha256 in lineage[packageName].orEmpty()
    }

    private class FakeSender : RouterBroadcastSender {
        val sent = mutableListOf<Triple<String, String, RoutedText>>()
        var code = RouterApi.RESULT_ACKNOWLEDGED
        var answerAfterMillis = 0L
        override suspend fun send(packageName: String, receiverClass: String, text: RoutedText): Int {
            sent += Triple(packageName, receiverClass, text)
            delay(answerAfterMillis)
            return code
        }
    }

    private val installed = FakeInstalled()
    private val sender = FakeSender()
    private var trusted = listOf(TrustedRouterApp("com.example.tasks", a, 1L))
    private val delivery = AppRouterDelivery(installed, { trusted }, sender, waitMillis = 200)

    private fun installTasks(namespace: String = a, id: String = "tasks") {
        installed.routers += InstalledRouter("com.example.tasks", "com.example.tasks.Router", id, namespace, "Tasks", null, "Tasks app")
    }

    private fun failed(block: suspend () -> Unit): CaptureProcessingException = assertFailsWith { runBlocking { block() } }

    private fun unavailable(block: suspend () -> Unit): CaptureProcessingException {
        val e = failed(block)
        assertEquals(CaptureFailureKind.FLAG, e.kind)
        assertEquals(CaptureFailureReason.ROUTER_UNAVAILABLE, e.reason)
        return e
    }

    @Test
    fun aTrustedInstalledRouterGetsTheTextAndItsAcknowledgementEndsTheDelivery() = runBlocking<Unit> {
        installTasks()
        delivery.deliver(tasks, text)
        assertEquals(listOf(Triple("com.example.tasks", "com.example.tasks.Router", text)), sender.sent)
    }

    @Test
    fun aRouterThatIsGoneOrUntrustedIsFlaggedWithoutASend() {
        unavailable { delivery.deliver(tasks, text) }
        installTasks()
        trusted = emptyList()
        assertTrue(unavailable { delivery.deliver(tasks, text) }.message!!.contains("not trusted"))
        assertTrue(sender.sent.isEmpty())
    }

    /** The config's ref pins the router: the receiver has to declare that id, in an app signed by that namespace. */
    @Test
    fun aReceiverThatDeclaresAnotherIdOrIsSignedByAnotherKeyIsFlaggedWithoutASend() {
        installTasks(id = "archive")
        assertTrue(unavailable { delivery.deliver(tasks, text) }.message!!.contains("different router"))
        installed.routers.clear()
        installTasks(namespace = "d".repeat(64))
        trusted = listOf(TrustedRouterApp("com.example.tasks", "d".repeat(64), 1L))
        assertTrue(unavailable { delivery.deliver(tasks, text) }.message!!.contains("different key"))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun aRotatedSignerStillAnswersForItsNamespaceAndTrustThroughThePlatformsLineageCheck() = runBlocking<Unit> {
        installTasks(namespace = "d".repeat(64))
        trusted = listOf(TrustedRouterApp("com.example.tasks", old, 1L))
        installed.lineage["com.example.tasks"] = setOf(old)
        delivery.deliver(tasks.copy(namespace = old), text)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun noAcknowledgementOrNoAnswerInTimeIsARetry() {
        installTasks()
        sender.code = 0
        val refused = failed { delivery.deliver(tasks, text) }
        assertEquals(CaptureFailureKind.RETRY, refused.kind)
        sender.code = RouterApi.RESULT_ACKNOWLEDGED
        sender.answerAfterMillis = 10_000
        val late = failed { delivery.deliver(tasks, text) }
        assertEquals(CaptureFailureKind.RETRY, late.kind)
        assertTrue(late.message!!.contains("did not answer"))
    }

    @Test
    fun onlyAnAppRouterCanBeDeliveredHere() {
        assertFailsWith<IllegalArgumentException> { runBlocking { delivery.deliver(RouterConfig.Note("n", "Notes"), text) } }
    }
}

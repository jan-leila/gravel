package com.anopticlabs.gravel.router.common

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterListTest {

    private val dir = Files.createTempDirectory("routers").toFile()
    private val list = RouterList(File(dir, RouterList.FILE_NAME))

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun router(id: String, label: String = id) = ListedRouter(id, label, "about $label", "recorded_at", mapOf("project" to "7"))

    @Test
    fun theListRoundTripsWithEveryPart() {
        val routers = listOf(router("vikunja-1", "Grocer\"ies\n"), ListedRouter("vikunja-2", "Bare", shared = false))
        list.replace(routers)
        assertEquals(routers, list.all())
        assertEquals(listOf("vikunja-1"), list.shared().map { it.id })
        assertEquals(1, dir.listFiles()!!.size, "no staging file is left behind")
    }

    /** A refresh keeps the user's ticks, gives a new router the default and drops one the service no longer lists. */
    @Test
    fun aMergeKeepsTheSharingTheUserChose() {
        list.merge(listOf(router("a"), router("b")), sharedByDefault = false)
        list.setShared("a", true)
        list.merge(listOf(router("a", "A renamed"), router("c"), router("c")), sharedByDefault = false)
        assertEquals(listOf("a" to true, "c" to false), list.all().map { it.id to it.shared })
        assertEquals("A renamed", list.find("a")!!.label)
        assertNull(list.find("b"))
    }

    @Test
    fun aDeclaredRouterIsAddedReplacedAndRemovedByItsId() {
        list.add(router("email-1", "Sam"))
        list.add(router("email-2", "Kim"))
        list.add(router("email-1", "Sam Doe"))
        assertEquals(listOf("Kim", "Sam Doe"), list.all().map { it.label })
        list.remove("email-2")
        assertEquals(listOf("email-1"), list.all().map { it.id })
    }

    @Test
    fun aDamagedFileIsAnEmptyListAndABadEntryIsLeftOut() {
        File(dir, RouterList.FILE_NAME).writeText("{not json")
        assertTrue(list.all().isEmpty())
        assertEquals(listOf("ok"), RouterList.decode("""[{"id":"ok","label":"Ok"},{"id":"Not An Id","label":"x"},{"id":"nolabel"},5]""").map { it.id })
    }

    @Test
    fun anIdIsStableForItsKeyAndOfTheGrammar() {
        assertEquals(RouterIds.of("slack", "C0123ABCD"), RouterIds.of("slack", "C0123ABCD"))
        assertTrue(RouterIds.of("slack", "C0123ABCD") != RouterIds.of("slack", "C0123ABCE"))
        assertTrue(RouterIds.isRouterId(RouterIds.of("homeassistant", "+49 170 / ü")))
        assertTrue(RouterIds.fresh("email") != RouterIds.fresh("email"))
    }
}

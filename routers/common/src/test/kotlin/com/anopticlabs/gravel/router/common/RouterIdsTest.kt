package com.anopticlabs.gravel.router.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every router app is signed with the one key, so their router ids share a namespace in Gravel's
 * routing and a user could never route to two apps declaring the same id (`ROUTER_API.md`).
 */
class RouterIdsTest {

    private val routers = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "routers") }
        .first { File(it, "README.md").isFile }

    @Test
    fun everyRouterAppDeclaresItsOwnId() {
        val id = Regex("android:name=\"com\\.anopticlabs\\.gravel\\.notebook\\.router\\.id\" android:value=\"([^\"]*)\"")
        val declared = routers.listFiles()!!
            .map { File(it, "src/main/AndroidManifest.xml") }
            .filter { it.isFile }
            .associate { manifest -> manifest.parentFile!!.parentFile!!.parentFile!!.name to id.findAll(manifest.readText()).map { it.groupValues[1] }.toList() }
            .filterValues { it.isNotEmpty() }
        assertEquals(8, declared.size, "the router apps: $declared")
        val ids = declared.values.flatten()
        assertEquals(ids.size, ids.toSet().size, "ids are shared: $declared")
        ids.forEach { assertTrue(Regex("[a-z0-9-]{1,32}").matches(it), it) }
    }

    /** A listed router's id is its app's prefix and a dash; a prefix another app shares would let two apps' routers collide. */
    @Test
    fun everyAppThatListsRoutersHasItsOwnPrefix() {
        val prefix = Regex("const val LISTED_PREFIX = \"([^\"]*)\"")
        val prefixes = routers.listFiles()!!
            .flatMap { app -> app.resolve("src/main").walkTopDown().filter { it.extension == "kt" }.mapNotNull { prefix.find(it.readText())?.groupValues?.get(1) }.toList() }
        assertEquals(6, prefixes.size, "the apps that list routers: $prefixes")
        assertEquals(prefixes.size, prefixes.toSet().size, "prefixes are shared: $prefixes")
        prefixes.forEach { RouterIds.of(it, "key") }
    }
}

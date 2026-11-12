package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DeliveryExtrasTest {

    private val here = RoutedLocation(52.52, 13.405, 30f)
    private val text = RoutedText("c1", "2026-09-22T17:00:00+02:00", "add a task: buy milk", here)

    /** The extras are what leaves the phone for an app: these names, and nothing else, for a delivery that shares nothing. */
    @Test
    fun aDeliveryWithoutMatchesOrSharesCarriesTheTextAndTheTextLeftOverAsTheSame() {
        val extras = deliveryExtras(null, text, emptySet())
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text"), extras.keys)
        assertEquals(RouterApi.VERSION, extras["api_version"])
        assertEquals("add a task: buy milk", extras["unmatched_text"])
    }

    @Test
    fun theSharedPartsAndAListedRoutersIdGoWithIt() {
        val extras = deliveryExtras("project-4", text, setOf(Need.RECORDED_AT, Need.LOCATION))
        assertEquals(RouterApi.VERSION_LISTED, extras["api_version"])
        assertEquals("project-4", extras["router_id"])
        assertEquals("2026-09-22T17:00:00+02:00", extras["recorded_at"])
        assertEquals(52.52, extras["location_latitude"])
        assertEquals(13.405, extras["location_longitude"])
        assertEquals(30f, extras["location_accuracy_m"])
    }

    @Test
    fun everyMatchIsOneEntryInEachOfTheMatchArrays() {
        val matched = text.copy(matches = listOf(TextMatch("tasks", "Tasks", 0, 10), TextMatch("shop", "Shopping", 16, 20)))
        val extras = deliveryExtras(null, matched, emptySet())
        assertEquals("buy", extras["unmatched_text"])
        @Suppress("UNCHECKED_CAST")
        fun strings(name: String) = (extras.getValue(name) as Array<String>).toList()
        assertEquals(listOf("tasks", "shop"), strings("match_router_ids"))
        assertEquals(listOf("Tasks", "Shopping"), strings("match_router_names"))
        assertContentEquals(intArrayOf(0, 16), extras["match_starts"] as IntArray)
        assertContentEquals(intArrayOf(10, 20), extras["match_ends"] as IntArray)
        assertEquals(listOf("add a task", "milk"), strings("match_texts"))
    }
}

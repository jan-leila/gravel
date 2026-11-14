package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryExtrasTest {

    private class Written : DeliveryExtras {
        val extras = mutableMapOf<String, Any>()
        override fun putString(name: String, value: String) { extras[name] = value }
        override fun putInt(name: String, value: Int) { extras[name] = value }
        override fun putDouble(name: String, value: Double) { extras[name] = value }
        override fun putFloat(name: String, value: Float) { extras[name] = value }
        override fun putStringArray(name: String, value: Array<String>) { extras[name] = value.toList() }
    }

    private val here = RoutedLocation(52.52, 13.405, 30f)
    private val text = RoutedText("c1", "2026-09-22T17:00:00+02:00", "add a task: buy milk", here)

    private fun written(listedId: String? = null, text: RoutedText = this.text, share: Set<Need> = emptySet(), fields: Map<String, String> = emptyMap()) =
        Written().also { writeDeliveryExtras(it, listedId, text, share, fields) }.extras

    /** The extras are what leaves the phone for an app: these names, and nothing else, for a delivery that shares nothing. */
    @Test
    fun aDeliveryWithoutCutsSharesOrFieldsCarriesTheTextTwice() {
        val extras = written()
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text"), extras.keys)
        assertEquals(RouterApi.VERSION, extras["api_version"])
        assertEquals("add a task: buy milk", extras["unmatched_text"])
    }

    @Test
    fun theSharedPartsAndAListedRoutersIdGoWithIt() {
        val extras = written("project-4", share = setOf(Need.RECORDED_AT, Need.LOCATION))
        assertEquals(RouterApi.VERSION_LISTED, extras["api_version"])
        assertEquals("project-4", extras["router_id"])
        assertEquals("2026-09-22T17:00:00+02:00", extras["recorded_at"])
        assertEquals(52.52, extras["location_latitude"])
        assertEquals(13.405, extras["location_longitude"])
        assertEquals(30f, extras["location_accuracy_m"])
    }

    /** The matches stay on the phone: a delivery carries the text they left and the fields mapped from them, not the matches. */
    @Test
    fun theCutTextAndTheMappedFieldsGoButNotTheMatches() {
        val matched = text.copy(matches = listOf(TextMatch("tasks", 0, 10, cut = true, groups = listOf(MatchGroup("item", 12, 20)))))
        val extras = written(text = matched, fields = mapOf("title" to "buy milk", "list" to "groceries"))
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text", "field_names", "field_values"), extras.keys)
        assertEquals("buy milk", extras["unmatched_text"])
        assertEquals(listOf("title", "list"), extras["field_names"])
        assertEquals(listOf("buy milk", "groceries"), extras["field_values"])
    }
}

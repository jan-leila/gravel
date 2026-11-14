package com.anopticlabs.gravel.router.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeliveryTest {

    private val full = mapOf(
        RouterContract.EXTRA_API_VERSION to 1,
        RouterContract.EXTRA_CAPTURE_ID to "c1",
        RouterContract.EXTRA_TEXT to "buy milk",
        RouterContract.EXTRA_RECORDED_AT to "2026-09-22T17:00:00+02:00",
        RouterContract.EXTRA_LOCATION_LATITUDE to 52.52,
        RouterContract.EXTRA_LOCATION_LONGITUDE to 13.405,
        RouterContract.EXTRA_LOCATION_ACCURACY_METERS to 30f,
    )

    @Test
    fun theExtrasAreReadWithTheOptionalPartsOptional() {
        assertEquals(Delivery("c1", "buy milk", "2026-09-22T17:00:00+02:00", Location(52.52, 13.405, 30f)), Delivery.from(full))
        val bare = full - RouterContract.EXTRA_RECORDED_AT - RouterContract.EXTRA_LOCATION_LATITUDE - RouterContract.EXTRA_LOCATION_LONGITUDE - RouterContract.EXTRA_LOCATION_ACCURACY_METERS
        assertEquals(Delivery("c1", "buy milk", null, null), Delivery.from(bare))
        assertEquals(Location(52.52, 13.405, null), Delivery.from(full - RouterContract.EXTRA_LOCATION_ACCURACY_METERS)!!.location)
    }

    @Test
    fun anotherVersionAMissingPartOrHalfALocationIsNotADelivery() {
        assertNull(Delivery.from(full + (RouterContract.EXTRA_API_VERSION to 3)))
        assertNull(Delivery.from(full + (RouterContract.EXTRA_API_VERSION to 2)), "a listed delivery names its router")
        assertNull(Delivery.from(full - RouterContract.EXTRA_API_VERSION))
        assertNull(Delivery.from(full - RouterContract.EXTRA_CAPTURE_ID))
        assertNull(Delivery.from(full - RouterContract.EXTRA_TEXT))
        assertNull(Delivery.from(full + (RouterContract.EXTRA_CAPTURE_ID to "")))
        assertNull(Delivery.from(full - RouterContract.EXTRA_LOCATION_LONGITUDE))
        assertNull(Delivery.from(full + (RouterContract.EXTRA_TEXT to 5)))
    }

    @Test
    fun aListedDeliveryCarriesItsRouterIdOfTheGrammar() {
        val listed = full + (RouterContract.EXTRA_API_VERSION to 2)
        assertEquals("vikunja-0123456789abcdef", Delivery.from(listed + (RouterContract.EXTRA_ROUTER_ID to "vikunja-0123456789abcdef"))!!.routerId)
        assertNull(Delivery.from(listed + (RouterContract.EXTRA_ROUTER_ID to "../x")))
        assertNull(Delivery.from(listed + (RouterContract.EXTRA_ROUTER_ID to 7)))
        assertNull(Delivery.from(full + (RouterContract.EXTRA_ROUTER_ID to "a"))!!.routerId, "a version 1 delivery is for the declared router")
        val stored = Delivery("c3", "x", null, null, "slack-1")
        assertEquals(stored, Delivery.read(ByteArrayInputStream(ByteArrayOutputStream().also { stored.write(it) }.toByteArray())))
    }

    private val matched = full + mapOf(
        RouterContract.EXTRA_TEXT to "add a task: buy milk",
        RouterContract.EXTRA_UNMATCHED_TEXT to "buy milk",
        RouterContract.EXTRA_FIELD_NAMES to arrayOf("title", "list"),
        RouterContract.EXTRA_FIELD_VALUES to arrayOf("Buy milk", " "),
    )

    @Test
    fun theFieldsAndTheTextLeftOverAreReadAndTheContentIsWhatIsLeft() {
        val delivery = Delivery.from(matched)!!
        assertEquals("buy milk", delivery.unmatchedText)
        assertEquals(mapOf("title" to "Buy milk", "list" to " "), delivery.fields)
        assertEquals("Buy milk", delivery.field("title"))
        assertNull(delivery.field("list"), "a blank field is no field")
        assertNull(delivery.field("other"))
        assertEquals("buy milk", delivery.content)
        assertEquals("buy milk", Delivery.from(full)!!.content, "a Gravel without them sends the text alone")
        assertEquals("todo", Delivery("c1", "todo", null, null, unmatchedText = " ").content, "nothing left over sends the whole text")
    }

    /** The field extras are optional: arrays that do not line up drop the fields, never the delivery. */
    @Test
    fun fieldArraysThatDoNotLineUpLeaveTheFieldsOut() {
        assertEquals(emptyMap<String, String>(), Delivery.from(matched + (RouterContract.EXTRA_FIELD_VALUES to arrayOf("x")))!!.fields)
        assertEquals(emptyMap<String, String>(), Delivery.from(matched - RouterContract.EXTRA_FIELD_NAMES)!!.fields)
        assertEquals(emptyMap<String, String>(), Delivery.from(matched + (RouterContract.EXTRA_FIELD_NAMES to "title"))!!.fields)
        assertEquals(16, Delivery.from(matched + mapOf(RouterContract.EXTRA_FIELD_NAMES to Array(20) { "p$it" }, RouterContract.EXTRA_FIELD_VALUES to Array(20) { "v" }))!!.fields.size)
    }

    @Test
    fun theFieldsRoundTripThroughTheOutboxFormat() {
        val delivery = Delivery.from(matched + (RouterContract.EXTRA_FIELD_VALUES to arrayOf("Buy = \"milk\"\nnow", "x")))!!
        assertEquals(delivery, Delivery.read(ByteArrayInputStream(ByteArrayOutputStream().also { delivery.write(it) }.toByteArray())))
    }

    @Test
    fun aDeliveryRoundTripsThroughTheOutboxFormatWithEveryCharacterKept() {
        val tricky = Delivery("c1", "line one\nline two = \"quoted\" é \\ end", "2026-09-22T17:00:00+02:00", Location(-33.9, 151.2, null))
        val bytes = ByteArrayOutputStream().also { tricky.write(it) }.toByteArray()
        assertEquals(tricky, Delivery.read(ByteArrayInputStream(bytes)))
        val bare = Delivery("c2", "x", null, null)
        assertEquals(bare, Delivery.read(ByteArrayInputStream(ByteArrayOutputStream().also { bare.write(it) }.toByteArray())))
        assertNull(Delivery.read(ByteArrayInputStream("text=only".encodeToByteArray())))
    }
}

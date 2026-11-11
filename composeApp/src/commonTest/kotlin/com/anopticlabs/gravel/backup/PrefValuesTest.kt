package com.anopticlabs.gravel.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class PrefValuesTest {
    @Test
    fun everyPreferenceTypeReadsBackAsItself() {
        val values = mapOf(
            "s" to "text",
            "b" to true,
            "i" to 7,
            "l" to 7L,
            "f" to 1.5f,
            "set" to setOf("b", "a"),
            "big" to Long.MAX_VALUE,
        )
        val decoded = PrefValues.decode(PrefValues.encode(values))
        assertEquals(values, decoded)
        assertEquals(Int::class, decoded.getValue("i")::class)
        assertEquals(Long::class, decoded.getValue("l")::class)
    }

    @Test
    fun aValueOfAnUnknownTypeIsLeftOutAndTheRestStillRead() {
        val json = JsonObject(
            mapOf(
                "ok" to JsonPrimitive("yes"),
                "odd" to JsonObject(mapOf("t" to JsonPrimitive("matrix"), "v" to JsonPrimitive(1))),
                "number" to JsonPrimitive(3),
            ),
        )
        assertEquals(mapOf<String, Any>("ok" to "yes"), PrefValues.decode(json))
    }

    @Test
    fun nullsAndUnsupportedValuesAreNotWritten() {
        assertEquals(setOf("kept"), PrefValues.encode(mapOf("kept" to "x", "gone" to null, "other" to listOf(1))).keys)
    }
}

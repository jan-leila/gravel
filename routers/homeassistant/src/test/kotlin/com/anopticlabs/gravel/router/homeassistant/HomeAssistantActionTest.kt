package com.anopticlabs.gravel.router.homeassistant

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.SettingValues
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeAssistantActionTest {

    private fun typed(service: String, entities: String = "", textField: String = "") =
        mapOf("name" to "Kitchen on", "service" to service, "entities" to entities, "text_field" to textField)

    @Test
    fun anActionIsSlugsOnly() {
        assertEquals(HomeAssistantAction("light", "turn_on", listOf("light.kitchen", "light.hall"), null), homeAssistantAction(typed("light.turn_on", " light.kitchen, light.hall ")))
        assertEquals(HomeAssistantAction("notify", "mobile_app_phone", emptyList(), "message"), homeAssistantAction(typed("notify.mobile_app_phone", textField = "message")))
        assertNull(homeAssistantAction(typed("light/../config.turn_on")))
        assertNull(homeAssistantAction(typed("turn_on")))
        assertNull(homeAssistantAction(typed("light.turn_on", "light.kitchen\"")))
        assertNull(homeAssistantAction(typed("light.turn_on", textField = "entity_id")), "the words cannot pick the entities")
        assertNull(homeAssistantAction(typed("Light.Turn_On")))
    }

    @Test
    fun theAddFormMakesARouterOrSaysWhatIsWrong() {
        val router = actionRouter(typed("light.turn_on", "light.kitchen"))
        assertEquals("Kitchen on", router.label)
        assertEquals("Calls light.turn_on on light.kitchen", router.description)
        assertTrue(router.id.startsWith("homeassistant-"))
        assertFailsWith<IllegalArgumentException> { actionRouter(typed("light")) }
        assertFailsWith<IllegalArgumentException> { actionRouter(typed("light.turn_on") + ("name" to " ")) }
    }

    @Test
    fun theRequestCallsTheActionWithTheEntitiesAndTheWordsWhenAsked() {
        val on = homeAssistantActionRequest("http://ha.lan:8123/", "tok", homeAssistantAction(typed("light.turn_on", "light.kitchen"))!!, Delivery("c1", "lights", null, null))
        assertEquals("POST", on.method)
        assertEquals("http://ha.lan:8123/api/services/light/turn_on", on.url)
        assertEquals(mapOf("Authorization" to "Bearer tok"), on.headers)
        assertEquals("""{"entity_id":["light.kitchen"]}""", on.body!!.decodeToString())
        val notify = homeAssistantActionRequest("http://ha.lan:8123", "tok", homeAssistantAction(typed("notify.phone", textField = "message"))!!, Delivery("c1", "say \"hi\"", null, null))
        assertEquals("""{"message":"say \"hi\""}""", notify.body!!.decodeToString())
    }

    @Test
    fun aListedActionIsCalledAndAGoneOneIsRefused() {
        val dir = Files.createTempDirectory("ha").toFile()
        try {
            val list = RouterList(File(dir, "list.json"))
            val router = actionRouter(typed("light.turn_on", "light.kitchen"))
            list.add(router)
            val settings = SettingValues { mapOf("url" to "http://ha.lan:8123", "token" to "tok")[it] }
            val urls = mutableListOf<String>()
            val sender = HomeAssistantSender(settings, list, { urls += it.url; HttpAnswer(200, "[]") }, { "en" })
            assertEquals(SendResult.Sent, sender.send(Delivery("c1", "x", null, null, router.id)))
            assertEquals(SendResult.Sent, sender.send(Delivery("c2", "x", null, null)))
            assertEquals(listOf("http://ha.lan:8123/api/services/light/turn_on", "http://ha.lan:8123/api/conversation/process"), urls)
            assertEquals(SendResult.Refused(RouterReceiver.NOT_LISTED), sender.send(Delivery("c3", "x", null, null, "homeassistant-0")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anActionsAnswerTellsAMissingActionFromBadData() {
        assertEquals(SendResult.Sent, HomeAssistantSender.actionAnswer(HttpAnswer(200, "[]")))
        assertEquals(SendResult.Refused("Home Assistant refused the action's data (HTTP 400)"), HomeAssistantSender.actionAnswer(HttpAnswer(400, "")))
        assertEquals(SendResult.Refused("Home Assistant has no such action, or no API at that address (HTTP 404)"), HomeAssistantSender.actionAnswer(HttpAnswer(404, "")))
        assertEquals(SendResult.Refused("Home Assistant refused the token (HTTP 401)"), HomeAssistantSender.actionAnswer(HttpAnswer(401, "")))
        assertEquals(SendResult.Retry("Home Assistant answered HTTP 502"), HomeAssistantSender.actionAnswer(HttpAnswer(502, "")))
    }
}

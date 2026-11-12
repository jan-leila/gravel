package com.anopticlabs.gravel.router.homeassistant

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.ListedRouter
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SettingValues
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException
import java.util.Locale

/** The Assist call: Home Assistant's REST API takes the sentence at `/api/conversation/process` with a bearer token. */
fun homeAssistantRequest(baseUrl: String, token: String, language: String, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = baseUrl.trimEnd('/') + "/api/conversation/process",
    headers = mapOf("Authorization" to "Bearer $token"),
    body = """{"text":${jsonString(delivery.content)},"language":${jsonString(language)}}""".encodeToByteArray(),
    contentType = "application/json",
)

/** One action the user set up: `domain.action`, the entities it targets, and the data field the words go in, if any. */
data class HomeAssistantAction(val domain: String, val action: String, val entities: List<String>, val textField: String?)

private val SLUG = Regex("[a-z0-9_]+")
private val ENTITY = Regex("[a-z0-9_]+\\.[a-z0-9_]+")

/** The action a router keeps in its values, or null for values that are not one: every part is a Home Assistant slug, so none can reach outside the action's path or body. */
fun homeAssistantAction(values: Map<String, String>): HomeAssistantAction? {
    val (domain, action) = values[HomeAssistantApp.ACTION_SERVICE]?.trim()?.split('.')?.takeIf { it.size == 2 && it.all(SLUG::matches) } ?: return null
    val entities = values[HomeAssistantApp.ACTION_ENTITIES].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (!entities.all(ENTITY::matches)) return null
    val textField = values[HomeAssistantApp.ACTION_TEXT_FIELD]?.trim()?.ifEmpty { null }
    if (textField != null && (!SLUG.matches(textField) || textField == "entity_id")) return null
    return HomeAssistantAction(domain, action, entities, textField)
}

/** An action's router, under a new id, from the add form; an action that is not one says what is wrong. */
fun actionRouter(typed: Map<String, String>): ListedRouter {
    val name = typed[HomeAssistantApp.ACTION_NAME].orEmpty().filterNot { it.isISOControl() }.trim()
    require(name.isNotEmpty()) { "The action needs a name." }
    val values = typed.filterKeys { it != HomeAssistantApp.ACTION_NAME }.mapValues { it.value.trim() }
    val action = requireNotNull(homeAssistantAction(values)) {
        "The action is domain.action and the entities domain.name, in lowercase letters, digits and underscores; the data field is one such word."
    }
    val target = action.entities.joinToString().ifEmpty { null }
    return ListedRouter(
        id = RouterIds.fresh(HomeAssistantApp.LISTED_PREFIX),
        label = name,
        description = "Calls ${action.domain}.${action.action}" + target?.let { " on $it" }.orEmpty(),
        values = values,
    )
}

/** The action call: Home Assistant's REST API takes it at `/api/services/<domain>/<action>` with the entities and the words in the body. */
fun homeAssistantActionRequest(baseUrl: String, token: String, action: HomeAssistantAction, delivery: Delivery): HttpRequest {
    val fields = buildList {
        if (action.entities.isNotEmpty()) add(""""entity_id":${action.entities.joinToString(",", "[", "]") { jsonString(it) }}""")
        action.textField?.let { add("${jsonString(it)}:${jsonString(delivery.content)}") }
    }
    return HttpRequest(
        method = "POST",
        url = baseUrl.trimEnd('/') + "/api/services/${action.domain}/${action.action}",
        headers = mapOf("Authorization" to "Bearer $token"),
        body = fields.joinToString(",", "{", "}").encodeToByteArray(),
        contentType = "application/json",
    )
}

/**
 * A 2xx is sent: Assist took the sentence, whatever it made of it. A 401 or 403 is a refusal
 * (the token), as is a 404 (no conversation API at that address); the rest is tried again. An
 * action is read by [actionAnswer].
 */
class HomeAssistantSender(
    private val settings: SettingValues,
    private val list: RouterList,
    private val send: (HttpRequest) -> HttpAnswer,
    private val defaultLanguage: () -> String = { Locale.getDefault().language.ifEmpty { "en" } },
) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val url = settings[HomeAssistantApp.KEY_URL] ?: return SendResult.Refused("The Home Assistant router is not set up")
        val token = settings[HomeAssistantApp.KEY_TOKEN] ?: return SendResult.Refused("The Home Assistant router has no token")
        if (!Http.allowed(url, allowPrivateCleartext = true)) return SendResult.Refused(HomeAssistantApp.BAD_ADDRESS)
        delivery.routerId?.let { id ->
            val values = list.find(id)?.values ?: return SendResult.Refused(RouterReceiver.NOT_LISTED)
            val action = homeAssistantAction(values) ?: return SendResult.Refused("The action of this router is not one Home Assistant takes")
            return actionAnswer(try {
                send(homeAssistantActionRequest(url, token, action, delivery))
            } catch (e: IOException) {
                return SendResult.Retry("Home Assistant could not be reached: ${e::class.simpleName}")
            })
        }
        val language = settings[HomeAssistantApp.KEY_LANGUAGE] ?: defaultLanguage()
        return answer(try {
            send(homeAssistantRequest(url, token, language, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("Home Assistant could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            401, 403 -> SendResult.Refused("Home Assistant refused the token (HTTP ${answer.status})")
            404 -> SendResult.Refused("Home Assistant has no Assist at that address (HTTP 404)")
            else -> answer.addressReason("Home Assistant")?.let(SendResult::Refused) ?: SendResult.Retry("Home Assistant answered HTTP ${answer.status}")
        }

        /** As [answer], but a 404 is an action Home Assistant does not have and a 400 data it does not take. */
        fun actionAnswer(answer: HttpAnswer): SendResult = when (answer.status) {
            400 -> SendResult.Refused("Home Assistant refused the action's data (HTTP 400)")
            404 -> SendResult.Refused("Home Assistant has no such action, or no API at that address (HTTP 404)")
            else -> HomeAssistantSender.answer(answer)
        }
    }
}

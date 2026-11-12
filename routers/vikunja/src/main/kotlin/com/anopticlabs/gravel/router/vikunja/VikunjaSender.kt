package com.anopticlabs.gravel.router.vikunja

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.ListedRouter
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.SettingValues
import com.anopticlabs.gravel.router.common.fetchJson
import com.anopticlabs.gravel.router.common.jsonString
import com.anopticlabs.gravel.router.common.scalar
import com.anopticlabs.gravel.router.common.string
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The task: Vikunja creates a task with PUT on the project's tasks; the text the routing left unmatched is the title, the recording time the description when granted. */
fun vikunjaRequest(baseUrl: String, token: String, projectId: String, delivery: Delivery): HttpRequest {
    val description = delivery.recordedAt?.let { ""","description":${jsonString("Recorded $it")}""" }.orEmpty()
    return HttpRequest(
        method = "PUT",
        url = baseUrl.trimEnd('/') + "/api/v1/projects/${projectId.trim()}/tasks",
        headers = mapOf("Authorization" to "Bearer $token"),
        body = """{"title":${jsonString(delivery.content)}$description}""".encodeToByteArray(),
        contentType = "application/json",
    )
}

/** One page of the projects the token can see. */
fun vikunjaProjectsRequest(baseUrl: String, token: String, page: Int): HttpRequest = HttpRequest(
    method = "GET",
    url = baseUrl.trimEnd('/') + "/api/v1/projects?page=$page&per_page=$PROJECTS_PER_PAGE",
    headers = mapOf("Authorization" to "Bearer $token"),
    answerLimit = Http.LIST_BODY_BYTES,
)

/**
 * The routers of one page of projects. A saved filter is listed with a negative id and takes no
 * tasks, and an archived project takes none either, so neither is a router.
 */
fun vikunjaProjectRouters(page: JsonArray): List<ListedRouter> = page.mapNotNull { element ->
    val project = element as? JsonObject ?: return@mapNotNull null
    val id = project.scalar("id")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    if ((project["is_archived"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
    val title = project.string("title") ?: "Project $id"
    ListedRouter(
        id = RouterIds.of(VikunjaApp.LISTED_PREFIX, id.toString()),
        label = "Vikunja: $title",
        description = "Adds a task to the Vikunja project $title",
        needs = "recorded_at",
        values = mapOf(VikunjaApp.VALUE_PROJECT to id.toString()),
    )
}

/** Every project, page by page, up to [MAX_PAGES]; the screen's refresh. */
@Throws(IOException::class)
fun vikunjaProjects(baseUrl: String, token: String, send: (HttpRequest) -> HttpAnswer): List<ListedRouter> {
    if (!Http.allowed(baseUrl, allowPrivateCleartext = true)) throw IOException(VikunjaApp.BAD_ADDRESS)
    val routers = mutableListOf<ListedRouter>()
    for (page in 1..MAX_PAGES) {
        val projects = fetchJson("Vikunja", send, vikunjaProjectsRequest(baseUrl, token, page)) as? JsonArray ?: throw IOException("Vikunja did not answer with a list")
        routers += vikunjaProjectRouters(projects)
        if (projects.size < PROJECTS_PER_PAGE) break
    }
    return routers
}

private const val PROJECTS_PER_PAGE = 50
private const val MAX_PAGES = 20

class VikunjaSender(private val settings: SettingValues, private val list: RouterList, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val url = settings[VikunjaApp.KEY_URL] ?: return SendResult.Refused("The Vikunja router is not set up")
        val token = settings[VikunjaApp.KEY_TOKEN] ?: return SendResult.Refused("The Vikunja router has no token")
        val project = when (val id = delivery.routerId) {
            null -> settings[VikunjaApp.KEY_PROJECT] ?: return SendResult.Refused(VikunjaApp.NO_DEFAULT)
            else -> list.find(id)?.values?.get(VikunjaApp.VALUE_PROJECT) ?: return SendResult.Refused(RouterReceiver.NOT_LISTED)
        }
        if (!project.trim().all(Char::isDigit)) return SendResult.Refused("The Vikunja project id has to be a number")
        if (!Http.allowed(url, allowPrivateCleartext = true)) return SendResult.Refused(VikunjaApp.BAD_ADDRESS)
        return answer(try {
            send(vikunjaRequest(url, token, project, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("Vikunja could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            401, 403 -> SendResult.Refused("Vikunja refused the token (HTTP ${answer.status})")
            404 -> SendResult.Refused("Vikunja has no such project (HTTP 404)")
            400, 412, 422 -> SendResult.Refused("Vikunja refused the task (HTTP ${answer.status})")
            else -> answer.addressReason("Vikunja")?.let(SendResult::Refused) ?: SendResult.Retry("Vikunja answered HTTP ${answer.status}")
        }
    }
}

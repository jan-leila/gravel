package com.anopticlabs.gravel.router.vikunja

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException

/** The task: Vikunja creates a task with PUT on the project's tasks; the text is the title, the recording time the description when granted. */
fun vikunjaRequest(baseUrl: String, token: String, projectId: String, delivery: Delivery): HttpRequest {
    val description = delivery.recordedAt?.let { ""","description":${jsonString("Recorded $it")}""" }.orEmpty()
    return HttpRequest(
        method = "PUT",
        url = baseUrl.trimEnd('/') + "/api/v1/projects/${projectId.trim()}/tasks",
        headers = mapOf("Authorization" to "Bearer $token"),
        body = """{"title":${jsonString(delivery.text)}$description}""".encodeToByteArray(),
        contentType = "application/json",
    )
}

class VikunjaSender(private val settings: Settings, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val url = settings[VikunjaApp.KEY_URL] ?: return SendResult.Refused("The Vikunja router is not set up")
        val token = settings[VikunjaApp.KEY_TOKEN] ?: return SendResult.Refused("The Vikunja router has no token")
        val project = settings[VikunjaApp.KEY_PROJECT]?.takeIf { it.trim().all(Char::isDigit) } ?: return SendResult.Refused("The Vikunja project id has to be a number")
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
            else -> SendResult.Retry("Vikunja answered HTTP ${answer.status}")
        }
    }
}

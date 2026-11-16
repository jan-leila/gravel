package com.anopticlabs.gravel.textserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class ChatMessageBody(val role: String, val content: String)

@Serializable
internal data class ChatRequestBody(
    val model: String,
    val messages: List<ChatMessageBody>,
    val stream: Boolean,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
)

private val requestJson = Json { encodeDefaults = true }

/** The most tokens a reply may ask for: the largest the Notebook's callers ask the phone's engine for. */
const val MAX_SERVER_REPLY_TOKENS = 2_048

/**
 * Gravel: an OpenAI chat completions request for one system and one user message, the conversation the phone's
 * engine renders. Both contents are trimmed as the phone's path trims them; temperature 0 matches the phone's
 * greedy sampling, so a reply depends on the prompt alone as far as the server allows; one reply, not streamed.
 */
fun chatRequestJson(model: String, system: String, user: String, maxTokens: Int): String = requestJson.encodeToString(
    ChatRequestBody.serializer(),
    ChatRequestBody(
        model = model.trim(),
        messages = listOf(ChatMessageBody("system", system.trim()), ChatMessageBody("user", user.trim())),
        stream = false,
        temperature = 0.0,
        maxTokens = maxTokens.coerceIn(1, MAX_SERVER_REPLY_TOKENS),
    ),
)

private val replyJson = Json { ignoreUnknownKeys = true }

/**
 * Gravel: `choices[0].message.content` of a chat completion reply with any reasoning block taken out
 * ([withoutThinking]), or null when the body is not such a reply or the content is not a string. An empty content
 * is a reply: the Notebook's parsers make their fallback of it, as they do of an empty reply on the phone.
 */
fun parseChatReply(body: String): String? {
    val root = runCatching { replyJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val message = choice["message"] as? JsonObject ?: return null
    val content = message["content"] as? JsonPrimitive ?: return null
    if (!content.isString) return null
    return withoutThinking(content.content)
}

private const val THINK_OPEN = "<think>"
private const val THINK_CLOSE = "</think>"

/**
 * Gravel: [content] without a reasoning model's `<think>` block. What follows the last close tag is the answer
 * (a template can open the block in the prompt, so a reply may carry the close tag alone); with no close tag, an
 * open one starts a block the token limit cut, which runs to the end.
 */
fun withoutThinking(content: String): String {
    val close = content.lastIndexOf(THINK_CLOSE)
    if (close >= 0) return content.substring(close + THINK_CLOSE.length).trim()
    val open = content.indexOf(THINK_OPEN)
    return (if (open >= 0) content.substring(0, open) else content).trim()
}

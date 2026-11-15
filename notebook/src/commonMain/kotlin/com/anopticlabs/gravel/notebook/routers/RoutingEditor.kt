package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.llm.LlmJson
import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A change to the routing the text model worked out, kept until the user applies it: the routing
 * it was worked out from ([base]) and the routing it gives ([proposed]), both with their secrets
 * out as the model saw them ([RoutingSecrets]), so a capture parked with one holds none of the
 * routing's tokens or coordinates, and [lines], what changes, for the user to read.
 */
@Serializable
data class RoutingChange(val base: RoutingConfig, val proposed: RoutingConfig, val lines: List<String>)

/** A change the model worked out: the [config] it gives, secrets back in and validated, and the [change] to keep for later. */
data class ProposedRouting(val config: RoutingConfig, val change: RoutingChange)

/** Why [RoutingEditor.propose] gave no change; each is the same again for the same routing and instruction. */
class RoutingEditRefused(val why: Why) : Exception(why.name) {
    enum class Why {
        /** The instruction is empty or over [RoutingEditor.MAX_INSTRUCTION_CHARS]. */
        INSTRUCTION,

        /** The routing is over [RoutingEditor.MAX_JSON_CHARS] as JSON. */
        TOO_LARGE,

        /** Every answer of the model was refused. */
        NO_ANSWER,
    }
}

/** Has the text model rewrite a routing from a description of the change, and applies what it proposed. */
object RoutingEditor {
    const val MAX_INSTRUCTION_CHARS = 1_000
    const val MAX_JSON_CHARS = 24 * 1024
    const val REPLY_TOKENS = 2_048
    const val ATTEMPTS = 2

    /**
     * The routing [instruction] makes of [current], as [text] answers it: the secrets are swapped
     * out before the model sees the routing and back afterwards, and the answer is validated like
     * any save. One retry with the reason when the first answer is refused. Nothing is stored; the
     * caller loads the model and frees it.
     */
    suspend fun propose(text: CaptureText, current: RoutingConfig, instruction: String): ProposedRouting {
        val asked = instruction.trim()
        if (asked.isEmpty() || asked.length > MAX_INSTRUCTION_CHARS) throw RoutingEditRefused(RoutingEditRefused.Why.INSTRUCTION)
        val redacted = RoutingSecrets.redact(current)
        val json = NotebookJson.encodeToString(RoutingConfig.serializer(), redacted.config)
        if (json.length > MAX_JSON_CHARS) throw RoutingEditRefused(RoutingEditRefused.Why.TOO_LARGE)
        var problem: String? = null
        repeat(ATTEMPTS) {
            val raw = text.generate(Prompts.ROUTING_EDIT_SYSTEM, Prompts.routingEditPrompt(json, asked, problem), REPLY_TOKENS)
            when (val outcome = proposalFrom(raw, current, redacted)) {
                is ProposedRouting -> return outcome
                is String -> problem = outcome
            }
        }
        throw RoutingEditRefused(RoutingEditRefused.Why.NO_ANSWER)
    }

    /**
     * The routing [change] gives when applied to [current], with the secrets [current] holds now.
     * Refused when [current] is no longer the routing the change was worked out from, a token or
     * a spot's coordinates aside: the user read what changes against that one.
     */
    fun resolve(change: RoutingChange, current: RoutingConfig): RoutingConfig {
        val redacted = RoutingSecrets.redact(current)
        require(redacted.config == change.base) { CHANGED_SINCE }
        return RoutingSecrets.restore(change.proposed, redacted).also { it.requireWellFormed() }
    }

    const val CHANGED_SINCE = "The routing changed since this change was proposed."

    /** The proposal in the model's [raw] answer, or the reason it is refused, for the retry prompt. */
    internal fun proposalFrom(raw: String, current: RoutingConfig, redacted: RedactedRouting): Any {
        val element = LlmJson.extract(LlmJson.cleanThinkAndFences(raw)) ?: return "the answer was not a JSON object"
        val decoded = try {
            NotebookJson.decodeFromJsonElement(RoutingConfig.serializer(), element)
        } catch (e: IllegalArgumentException) {
            return "the JSON did not have the routing's shape: ${e.message?.take(200)}"
        }
        return try {
            val restored = RoutingSecrets.restore(decoded, redacted)
            restored.requireWellFormed()
            ProposedRouting(restored, RoutingChange(redacted.config, decoded, RoutingDiff.describe(current, restored)))
        } catch (e: IllegalArgumentException) {
            e.message ?: "the routing was refused"
        }
    }
}

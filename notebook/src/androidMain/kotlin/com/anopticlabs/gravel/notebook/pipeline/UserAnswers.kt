package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.answerUser
import com.anopticlabs.gravel.notebook.capture.confirmUser
import com.anopticlabs.gravel.notebook.capture.requireConfirmation
import com.anopticlabs.gravel.notebook.routers.Grant
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RoutingEdits
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.shares
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.QueueStore.AwaitingRemoval
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What the capture rows do with a capture parked for the user: answer a question, confirm, or
 * dismiss. A dismiss leaves alone the capture the drain is serving ([processing]).
 */
class UserAnswers(
    private val queue: QueueStore,
    private val routing: RoutingConfigStore,
    private val drain: CaptureDrain,
    private val processing: (String) -> Boolean,
    private val requestAgain: () -> Unit,
) {
    /** How an answer ended: taken, taken but the grant the user asked to keep could not be written, or the capture was not waiting. */
    enum class Answered { TAKEN, GRANT_NOT_KEPT, NOT_WAITING }

    /**
     * Records the answer to one question of the parked capture [id]; with [remember], the grant of
     * its router becomes Always or Never, so the question is not asked again. The last answer
     * resumes the capture at once unless its router asks for a confirmation too. Outlives a closed
     * screen: an answer given is never dropped.
     */
    suspend fun answer(id: String, need: Need, allow: Boolean, remember: Boolean): Answered = withContext(NonCancellable) {
        val parked = queue.readQueue(strict = true).firstOrNull { it.id == id }?.awaiting ?: return@withContext Answered.NOT_WAITING
        var updated = queue.update { answerUser(it, id, need, allow) }.firstOrNull { it.id == id }?.awaiting ?: return@withContext Answered.NOT_WAITING
        val config = try {
            routing.read()
        } catch (e: Exception) {
            logger.w { "routing not read for an answer to ${parked.routerName}: ${e::class.simpleName}" }
            null
        }
        val router = config?.router(parked.router)
        var kept = true
        if (remember) {
            kept = try {
                // A router that is gone, or now of a kind without grants, has nothing to keep the answer on.
                if (config == null || router?.shares == null) {
                    false
                } else {
                    routing.write(RoutingEdits.withGrant(config, parked.router, need, if (allow) Grant.ALWAYS else Grant.NEVER))
                    true
                }
            } catch (e: Exception) {
                logger.w { "grant for ${parked.routerName} not kept: ${e::class.simpleName}" }
                false
            }
        }
        // A router set to ask first while the capture waited gets its confirmation: the snapshot was taken before.
        if (updated.asks.isEmpty() && !updated.confirm && router?.confirm == true) {
            updated = queue.update { requireConfirmation(it, id) }.firstOrNull { it.id == id }?.awaiting ?: updated
        }
        if (updated.settled) resume(id)
        if (kept) Answered.TAKEN else Answered.GRANT_NOT_KEPT
    }

    /** The user confirmed the parked capture [id]: it is taken off its confirmation and the next run takes it up. */
    suspend fun confirm(id: String) = withContext(NonCancellable) {
        queue.update { confirmUser(it, id) }
        resume(id)
    }

    /** Dismisses the parked capture [id], recording and all; one the drain is serving stays. */
    suspend fun dismiss(id: String): AwaitingRemoval = withContext(NonCancellable) {
        queue.removeAwaiting(id, processing)
    }

    private fun resume(id: String) {
        drain.requestResume(id)
        requestAgain()
    }

    private companion object {
        val logger = Logger.withTag("UserAnswers")
    }
}

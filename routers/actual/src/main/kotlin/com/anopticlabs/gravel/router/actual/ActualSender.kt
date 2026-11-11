package com.anopticlabs.gravel.router.actual

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException
import java.time.LocalDate

/**
 * The transaction, as actual-http-api takes it under the budget and the account: a spend (the
 * amount negative, in minor units), the payee by name, the words as the note, the capture id as
 * the imported id so a delivery Gravel repeats is the same transaction, dated the recording's day
 * when granted and [today] otherwise.
 */
fun actualRequest(relay: String, apiKey: String, budget: String, account: String, delivery: Delivery, transaction: TextTransaction, today: LocalDate): HttpRequest {
    val date = delivery.recordedAt?.takeIf { it.length >= 10 }?.substring(0, 10) ?: today.toString()
    val payee = transaction.payee?.let { ""","payee_name":${jsonString(it)}""" }.orEmpty()
    val body = """{"transaction":{"account":${jsonString(account)},"date":${jsonString(date)},"amount":${-transaction.amountMinor}$payee,"notes":${jsonString(transaction.notes)},"imported_id":${jsonString(delivery.captureId)}}}"""
    return HttpRequest(
        method = "POST",
        url = relay.trimEnd('/') + "/v1/budgets/${budget.trim()}/accounts/${account.trim()}/transactions",
        headers = mapOf("x-api-key" to apiKey),
        body = body.encodeToByteArray(),
        contentType = "application/json",
    )
}

class ActualSender(
    private val settings: Settings,
    private val send: (HttpRequest) -> HttpAnswer,
    private val today: () -> LocalDate = { LocalDate.now() },
) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val relay = settings[ActualApp.KEY_URL] ?: return SendResult.Refused("The Actual router is not set up")
        val apiKey = settings[ActualApp.KEY_API_KEY] ?: return SendResult.Refused("The Actual router has no API key")
        val budget = settings[ActualApp.KEY_BUDGET] ?: return SendResult.Refused("The Actual router has no budget")
        val account = settings[ActualApp.KEY_ACCOUNT] ?: return SendResult.Refused("The Actual router has no account")
        if (!Http.allowed(relay, allowPrivateCleartext = true)) return SendResult.Refused(ActualApp.BAD_ADDRESS)
        val decimals = settings[ActualApp.KEY_DECIMALS]?.toIntOrNull()?.coerceIn(0, 4) ?: 2
        val transaction = TextTransactions.parse(delivery.text, decimals) ?: return SendResult.Refused(ActualApp.NO_AMOUNT)
        return answer(try {
            send(actualRequest(relay, apiKey, budget, account, delivery, transaction, today()))
        } catch (e: IOException) {
            return SendResult.Retry("The Actual relay could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            400, 404, 422 -> SendResult.Refused("The Actual relay refused the transaction (HTTP ${answer.status})")
            401, 403 -> SendResult.Refused("The Actual relay refused the API key (HTTP ${answer.status})")
            else -> answer.addressReason("The Actual relay")?.let(SendResult::Refused) ?: SendResult.Retry("The Actual relay answered HTTP ${answer.status}")
        }
    }
}

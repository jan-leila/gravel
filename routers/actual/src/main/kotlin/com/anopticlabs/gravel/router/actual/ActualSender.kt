package com.anopticlabs.gravel.router.actual

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
import com.anopticlabs.gravel.router.common.fetchJson
import com.anopticlabs.gravel.router.common.string
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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

/** The accounts of the budget, as the relay lists them under `data`. */
fun actualAccountsRequest(relay: String, apiKey: String, budget: String): HttpRequest = HttpRequest(
    method = "GET",
    url = relay.trimEnd('/') + "/v1/budgets/${budget.trim()}/accounts",
    headers = mapOf("x-api-key" to apiKey),
    answerLimit = Http.LIST_BODY_BYTES,
)

/** The routers of the budget's accounts; a closed account takes no transactions, so it is not one. */
@Throws(IOException::class)
fun actualAccounts(relay: String, apiKey: String, budget: String, send: (HttpRequest) -> HttpAnswer): List<ListedRouter> {
    if (!Http.allowed(relay, allowPrivateCleartext = true)) throw IOException(ActualApp.BAD_ADDRESS)
    val answer = fetchJson("The Actual relay", send, actualAccountsRequest(relay, apiKey, budget)) as? JsonObject
    val accounts = answer?.get("data") as? JsonArray ?: throw IOException("The Actual relay did not answer with a list")
    return accounts.mapNotNull { element ->
        val account = element as? JsonObject ?: return@mapNotNull null
        val id = account.string("id") ?: return@mapNotNull null
        if ((account["closed"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
        val name = account.string("name") ?: "Account"
        ListedRouter(
            id = RouterIds.of(ActualApp.LISTED_PREFIX, id),
            label = "Actual: $name",
            description = "Books a spend into the Actual account $name",
            needs = "recorded_at",
            values = mapOf(ActualApp.VALUE_ACCOUNT to id),
        )
    }
}

class ActualSender(
    private val settings: SettingValues,
    private val list: RouterList,
    private val send: (HttpRequest) -> HttpAnswer,
    private val today: () -> LocalDate = { LocalDate.now() },
) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val relay = settings[ActualApp.KEY_URL] ?: return SendResult.Refused("The Actual router is not set up")
        val apiKey = settings[ActualApp.KEY_API_KEY] ?: return SendResult.Refused("The Actual router has no API key")
        val budget = settings[ActualApp.KEY_BUDGET] ?: return SendResult.Refused("The Actual router has no budget")
        val account = when (val id = delivery.routerId) {
            null -> settings[ActualApp.KEY_ACCOUNT] ?: return SendResult.Refused(ActualApp.NO_DEFAULT)
            else -> list.find(id)?.values?.get(ActualApp.VALUE_ACCOUNT) ?: return SendResult.Refused(RouterReceiver.NOT_LISTED)
        }
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

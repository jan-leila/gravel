package com.anopticlabs.gravel.router.actual

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.SendResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActualSenderTest {

    @Test
    fun theWordsAreReadAsAnAmountAPayeeAndTheNote() {
        assertEquals(TextTransaction(1250, "the bakery", "12.50 at the bakery for bread"), TextTransactions.parse("12.50 at the bakery for bread"))
        assertEquals(TextTransaction(1250, "the bakery", "12,50 at the bakery, bread"), TextTransactions.parse("12,50 at the bakery, bread"))
        assertEquals(TextTransaction(700, "Sam", "paid 7 to Sam on Tuesday"), TextTransactions.parse("paid 7 to Sam on Tuesday"))
        assertEquals(TextTransaction(350, null, "coffee 3.5"), TextTransactions.parse("coffee 3.5"))
        assertEquals(TextTransaction(1250, "the bakery", "spent 12.50 at the bakery."), TextTransactions.parse("spent 12.50 at the bakery."))
        assertEquals(TextTransaction(12, null, "12 yen"), TextTransactions.parse("12 yen", decimals = 0))
        assertEquals(TextTransaction(125, null, "1.25 x"), TextTransactions.parse("1.25 x", decimals = 2))
        assertNull(TextTransactions.parse("groceries at the shop"))
        assertNull(TextTransactions.parse(""))
        assertEquals(TextTransaction(4000, null, "forty 40 whatever 2.5"), TextTransactions.parse("forty 40 whatever 2.5"), "the first number wins")
    }

    @Test
    fun theRequestIsASpendUnderTheBudgetAndAccountDatedTheRecordingWhenGranted() {
        val transaction = TextTransactions.parse("12.50 at the bakery for bread")!!
        val timed = actualRequest("http://budget.lan:5007/", "key", " b1 ", "a1", Delivery("c1", "12.50 at the bakery for bread", "2026-09-22T17:00:00+02:00", null), transaction, LocalDate.of(2026, 10, 30))
        assertEquals("POST", timed.method)
        assertEquals("http://budget.lan:5007/v1/budgets/b1/accounts/a1/transactions", timed.url)
        assertEquals(mapOf("x-api-key" to "key"), timed.headers)
        assertEquals(
            """{"transaction":{"account":"a1","date":"2026-09-22","amount":-1250,"payee_name":"the bakery","notes":"12.50 at the bakery for bread","imported_id":"c1"}}""",
            timed.body!!.decodeToString(),
        )
        val undated = actualRequest("http://budget.lan:5007", "key", "b1", "a1", Delivery("c2", "coffee 3.5", null, null), TextTransactions.parse("coffee 3.5")!!, LocalDate.of(2026, 10, 30))
        assertEquals("""{"transaction":{"account":"a1","date":"2026-10-30","amount":-350,"notes":"coffee 3.5","imported_id":"c2"}}""", undated.body!!.decodeToString())
    }

    @Test
    fun aBadTransactionOrKeyIsARefusalAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, ActualSender.answer(HttpAnswer(200, "{}")))
        assertEquals(SendResult.Refused("The Actual relay refused the transaction (HTTP 400)"), ActualSender.answer(HttpAnswer(400, "")))
        assertEquals(SendResult.Refused("The Actual relay refused the API key (HTTP 401)"), ActualSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Retry("The Actual relay answered HTTP 502"), ActualSender.answer(HttpAnswer(502, "")))
        assertEquals(SendResult.Refused("The Actual relay redirected the request (HTTP 308); check the address"), ActualSender.answer(HttpAnswer(308, "")), "a redirect is an address no retry changes")
    }
}

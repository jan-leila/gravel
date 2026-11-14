package com.anopticlabs.gravel.router.actual

import com.anopticlabs.gravel.router.common.Delivery

/**
 * What a spoken transaction says: "12 50 at the bakery for bread" is read as an amount, a payee
 * and the words as the note. [amountMinor] is in the currency's minor unit, positive; the sender
 * makes it a spend. Amounts are read from digits, as the speech engine writes them; a number
 * spoken as words is not read.
 */
data class TextTransaction(val amountMinor: Long, val payee: String?, val notes: String)

object TextTransactions {
    // The fraction follows a point or a comma, or is two digits on their own after a space or a colon ("twelve fifty" transcribed as "12 50").
    private val AMOUNT = Regex("(?<![\\d.,])(\\d{1,9})(?:[.,](\\d{1,2})|[ :](\\d{2})(?!\\d))?(?![\\d])")
    private val PAYEE = Regex("\\b(?:at|to)\\s+(.+?)(?=\\s+(?:for|on|,)\\b|[,.;]|$)", RegexOption.IGNORE_CASE)

    /**
     * The transaction of [delivery]: the amount, payee and notes parameters where the user mapped
     * them, the rest read from the words as [parse] reads them; null when there is no amount.
     */
    fun of(delivery: Delivery, decimals: Int = 2): TextTransaction? {
        val words = parse(delivery.content, decimals)
        val amount = delivery.field("amount")?.let { parse(it, decimals) ?: return null } ?: words ?: return null
        return TextTransaction(amount.amountMinor, delivery.field("payee") ?: words?.payee, delivery.field("notes") ?: delivery.content.trim())
    }

    /**
     * The transaction in [text], or null when no amount is in it. The first number with up to two
     * decimals is the amount, in [decimals] minor digits (a number without decimals is whole
     * units); the words after "at" or "to", up to "for", "on" or a comma, are the payee.
     */
    fun parse(text: String, decimals: Int = 2): TextTransaction? {
        val amount = AMOUNT.find(text) ?: return null
        val whole = amount.groupValues[1].toLong()
        val fraction = amount.groupValues[2].ifEmpty { amount.groupValues[3] }.padEnd(decimals, '0').take(decimals).ifEmpty { "0" }.toLong()
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val minor = whole * scale + fraction
        val payee = PAYEE.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')?.ifEmpty { null }
        return TextTransaction(minor, payee, text.trim())
    }
}

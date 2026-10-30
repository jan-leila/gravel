package com.anopticlabs.gravel.router.actual

/**
 * What a spoken transaction says: "twelve fifty at the bakery for bread" is read as an amount, a
 * payee and the words as the note. [amountMinor] is in the currency's minor unit, positive; the
 * sender makes it a spend.
 */
data class TextTransaction(val amountMinor: Long, val payee: String?, val notes: String)

object TextTransactions {
    private val AMOUNT = Regex("(?<![\\d.,])(\\d{1,9})(?:[.,](\\d{1,2}))?(?![\\d])")
    private val PAYEE = Regex("\\b(?:at|to)\\s+(.+?)(?=\\s+(?:for|on|,)\\b|[,.;]|$)", RegexOption.IGNORE_CASE)

    /**
     * The transaction in [text], or null when no amount is in it. The first number with up to two
     * decimals is the amount, in [decimals] minor digits (a number without decimals is whole
     * units); the words after "at" or "to", up to "for", "on" or a comma, are the payee.
     */
    fun parse(text: String, decimals: Int = 2): TextTransaction? {
        val amount = AMOUNT.find(text) ?: return null
        val whole = amount.groupValues[1].toLong()
        val fraction = amount.groupValues[2].padEnd(decimals, '0').take(decimals).ifEmpty { "0" }.toLong()
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val minor = whole * scale + fraction
        val payee = PAYEE.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')?.ifEmpty { null }
        return TextTransaction(minor, payee, text.trim())
    }
}

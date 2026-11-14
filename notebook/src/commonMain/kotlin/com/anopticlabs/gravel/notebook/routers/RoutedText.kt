package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.RecordedAt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/** Where a recording was made, as the phone knew it when the recording started. */
@Serializable
data class RoutedLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

/** A named group of a [TextMatch] and the span of [RoutedText.text] it took, in UTF-16 units, [end] exclusive. */
@Serializable
data class MatchGroup(val name: String, val start: Int, val end: Int)

/**
 * A Switch rule that matched on the way to the router: the switch by id (a switch is always one
 * of Gravel's own routers), the span of [RoutedText.text] the rule matched, in UTF-16 units,
 * [end] exclusive, whether the rule [cut]s those words from the text a router sends, and the
 * named [groups] that took part in the match.
 */
@Serializable
data class TextMatch(val routerId: String, val start: Int, val end: Int, val cut: Boolean = false, val groups: List<MatchGroup> = emptyList())

/**
 * What a voice recording's transcript is routed as: the whole text, the time the recording
 * started, where it was made when the phone knew, and the watch app open on the watch then
 * ([openApp], its UUID, which only the routing reads: no delivery carries it). Every router receives it as one value and
 * forwards it whole; [recordedAt] is a [RecordedAt] value; [captureId] identifies the recording,
 * so a receiver can tell a repeated delivery of the same recording from a new one; [matches] are
 * the Switch rules that matched on the way, in walk order, which stay on the phone: what a
 * delivery carries of them is the unmatched text and the fields the user mapped
 * ([RouterConfig.fieldsFor]). These are the fields of the router API (`ROUTER_API.md`): a field
 * added here can reach every app and server a user routes to, so what a delivery carries beyond
 * the text is the user's to grant per router.
 */
@Serializable
data class RoutedText(
    val captureId: String,
    val recordedAt: String,
    val text: String,
    val location: RoutedLocation? = null,
    val matches: List<TextMatch> = emptyList(),
    val openApp: String? = null,
) {
    /** [text] without the spans of the [matches] that cut theirs: what a router acts on once the words that routed it are gone. */
    val unmatchedText: String get() = unmatched(text, matches.filter { it.cut }.map { it.start to it.end })

    /** The words from [start] to [end] of the text, the span clamped to it. */
    fun slice(start: Int, end: Int): String {
        val from = start.coerceIn(0, text.length)
        return text.substring(from, end.coerceIn(from, text.length))
    }

    /** The value of every named group that took part in a match; a name two matches share takes the later one, nearer the router. */
    fun groupValues(): Map<String, String> = buildMap {
        for (match in matches) for (group in match.groups) put(group.name, slice(group.start, group.end))
    }

    companion object {
        fun of(captureId: String, recordedAt: Instant, zone: TimeZone, text: String, location: RoutedLocation? = null, openApp: String? = null): RoutedText =
            RoutedText(captureId, RecordedAt.render(recordedAt, zone), text, location, openApp = openApp)

        /**
         * [text] with every one of [spans] cut out, overlapping spans merged, the pieces left
         * joined by one space; the whole text when there is nothing to cut. At a cut, the spaces
         * and the commas, colons, semicolons and spaced dashes there go with it ("Task: buy
         * milk", "buy milk, todo, and eggs"); a sign, a decimal point and a sentence's end stay,
         * and so do the text's own ends. A span past the text is clamped to it, as matches come
         * back from storage.
         */
        fun unmatched(text: String, spans: List<Pair<Int, Int>>): String {
            val cuts = spans
                .map { (start, end) -> start.coerceIn(0, text.length) to end.coerceIn(0, text.length) }
                .filter { (start, end) -> end > start }
                .sortedBy { it.first }
            if (cuts.isEmpty()) return text
            val pieces = mutableListOf<String>()
            var from = 0
            for ((start, end) in cuts) {
                if (start > from) pieces += text.substring(from, start).let { if (from > 0) it.replace(LEADING_SEAM, "") else it }.replace(TRAILING_SEAM, "")
                from = maxOf(from, end)
            }
            pieces += text.substring(from).replace(LEADING_SEAM, "")
            // A piece that opens with a sentence's end ("buy milk, todo.") closes the one before it.
            return pieces.filter { it.isNotEmpty() }.ifEmpty { return "" }.reduce { joined, piece -> if (piece.first() in ".!?") joined + piece else "$joined $piece" }
        }

        private val LEADING_SEAM = Regex("^(?:[\\s,:;]+|[–—-]\\s)+")
        private val TRAILING_SEAM = Regex("(?:[\\s,:;]+|\\s[–—-])+$")
    }
}

package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.RecordedAt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/** Where a recording was made, as the phone knew it when the recording started. */
@Serializable
data class RoutedLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

/**
 * A Switch rule that matched on the way to the router: the switch by id and name, and the span of
 * [RoutedText.text] its pattern matched, in UTF-16 units, [end] exclusive. A switch is always one
 * of Gravel's own routers, so its id needs no namespace.
 */
@Serializable
data class TextMatch(val routerId: String, val routerName: String, val start: Int, val end: Int)

/**
 * What a voice recording's transcript is routed as: the whole text, the time the recording
 * started, and where it was made when the phone knew. Every router receives it as one value and
 * forwards it whole; [recordedAt] is a [RecordedAt] value; [captureId] identifies the recording,
 * so a receiver can tell a repeated delivery of the same recording from a new one; [matches] are
 * the Switch rules that matched on the way, in walk order. These are the fields of the router API
 * (`ROUTER_API.md`): a field added here can reach every app and server a user routes to, so what a
 * delivery carries beyond the text is the user's to grant per router.
 */
@Serializable
data class RoutedText(
    val captureId: String,
    val recordedAt: String,
    val text: String,
    val location: RoutedLocation? = null,
    val matches: List<TextMatch> = emptyList(),
) {
    /** [text] without the spans [matches] cover: what a router acts on once the words that routed it are gone. */
    val unmatchedText: String get() = unmatched(text, matches)

    /** The words [match] covers, its span clamped to the text. */
    fun matchedText(match: TextMatch): String {
        val start = match.start.coerceIn(0, text.length)
        return text.substring(start, match.end.coerceIn(start, text.length))
    }

    companion object {
        fun of(captureId: String, recordedAt: Instant, zone: TimeZone, text: String, location: RoutedLocation? = null): RoutedText =
            RoutedText(captureId, RecordedAt.render(recordedAt, zone), text, location)

        /**
         * [text] with every span of [matches] cut out, overlapping spans merged, the pieces left
         * joined by one space; the whole text when nothing matched. The separators at a cut go
         * with the match ("Task: buy milk", "buy milk, todo, and eggs"); the text's own ends keep
         * theirs. A stored span past the text is clamped to it.
         */
        fun unmatched(text: String, matches: List<TextMatch>): String {
            val spans = matches
                .map { it.start.coerceIn(0, text.length) to it.end.coerceIn(0, text.length) }
                .filter { (start, end) -> end > start }
                .sortedBy { it.first }
            if (spans.isEmpty()) return text
            // Each piece is (text, follows a cut, precedes a cut).
            val pieces = mutableListOf<Triple<String, Boolean, Boolean>>()
            var from = 0
            for ((start, end) in spans) {
                if (start > from) pieces += Triple(text.substring(from, start), from > 0, true)
                from = maxOf(from, end)
            }
            pieces += Triple(text.substring(from), true, false)
            return pieces.map { (piece, afterCut, beforeCut) ->
                var kept = piece
                if (afterCut) kept = kept.replace(LEADING_SEAM, "")
                if (beforeCut) kept = kept.replace(TRAILING_SEAM, "")
                kept.trim()
            }.filter { it.isNotEmpty() }.joinToString(" ")
        }

        private val LEADING_SEAM = Regex("^[\\s,.:;!?–—-]+")
        private val TRAILING_SEAM = Regex("[\\s,.:;!?–—-]+$")
    }
}

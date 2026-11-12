package com.anopticlabs.gravel.notebook.routers

/**
 * The contract between Gravel and an app that declares a router (`ROUTER_API.md`): the action its
 * receiver filters on, the permission that guards the receiver, the meta-data that names it, and
 * the extras of the broadcast that carries a [RoutedText]. Every name here is public API: a
 * change breaks installed apps, so a new field gets a new name and [VERSION] moves.
 */
object RouterApi {
    const val VERSION = 1

    /** The intent-filter action of a router receiver, and the action of every delivery. */
    const val ACTION_ROUTE_TEXT = "com.anopticlabs.gravel.notebook.action.ROUTE_TEXT"

    /**
     * Signature-level, defined by Gravel, so a receiver that names it as its `android:permission`
     * accepts broadcasts from Gravel alone; Gravel sends only to receivers guarded by it.
     */
    const val PERMISSION_ROUTE_TEXT = "com.anopticlabs.gravel.permission.ROUTE_NOTEBOOK_TEXT"

    /**
     * `<meta-data>` on the receiver: the router's id within its app's namespace (the id grammar of
     * [RoutingConfig.isRouterId]; a receiver without one is not a router), what the router is called in
     * Gravel's settings, and a line about it.
     */
    const val META_ID = "com.anopticlabs.gravel.notebook.router.id"
    const val META_LABEL = "com.anopticlabs.gravel.notebook.router.label"
    const val META_DESCRIPTION = "com.anopticlabs.gravel.notebook.router.description"

    /**
     * `<meta-data>` on the receiver: what it wants beyond the text, as a comma-separated list of
     * [NEED_LOCATION] and [NEED_RECORDED_AT]; absent, the text alone. See [parseNeeds].
     */
    const val META_NEEDS = "com.anopticlabs.gravel.notebook.router.needs"
    const val NEED_LOCATION = "location"
    const val NEED_RECORDED_AT = "recorded_at"

    /**
     * `<meta-data>` on the receiver: the authority of a content provider in the same app that lists
     * further routers delivered to this receiver, one row each under [LIST_PATH] with the
     * [COLUMN_ID], [COLUMN_LABEL], [COLUMN_DESCRIPTION] and [COLUMN_NEEDS] columns. The provider
     * has to be exported and guarded by [PERMISSION_ROUTE_TEXT], as the receiver is.
     */
    const val META_LIST = "com.anopticlabs.gravel.notebook.router.list"
    const val LIST_PATH = "routers"
    const val COLUMN_ID = "id"
    const val COLUMN_LABEL = "label"
    const val COLUMN_DESCRIPTION = "description"
    const val COLUMN_NEEDS = "needs"

    /** Rows past this many are not read, so one app cannot flood the settings screen. */
    const val MAX_LISTED = 200

    /** How long a list query may take before the app's listed routers count as absent; the provider runs in another app. */
    const val LIST_WAIT_MILLIS = 5_000L

    /** The [EXTRA_API_VERSION] of a delivery to a listed router, which also carries [EXTRA_ROUTER_ID]; a declared router's deliveries stay [VERSION]. */
    const val VERSION_LISTED = 2

    /** The id of the listed router a delivery is for. */
    const val EXTRA_ROUTER_ID = "router_id"

    const val EXTRA_API_VERSION = "api_version"
    const val EXTRA_CAPTURE_ID = "capture_id"
    const val EXTRA_TEXT = "text"

    /** Present only when the receiver declared the need and the user granted it. */
    const val EXTRA_RECORDED_AT = "recorded_at"

    /** Present together, under the same rule as [EXTRA_RECORDED_AT]; the accuracy may be absent when the fix had none. */
    const val EXTRA_LOCATION_LATITUDE = "location_latitude"
    const val EXTRA_LOCATION_LONGITUDE = "location_longitude"
    const val EXTRA_LOCATION_ACCURACY_METERS = "location_accuracy_m"

    /** The result code a receiver sets to acknowledge a delivery: `Activity.RESULT_OK`. Any other code but [RESULT_REFUSED] is no acknowledgement. */
    const val RESULT_ACKNOWLEDGED = -1

    /**
     * The result code a receiver sets to refuse a delivery for good (`Activity.RESULT_FIRST_USER`),
     * its result data saying why in up to [MAX_REFUSAL_CHARS]: the recording is flagged with that
     * reason and never delivered again.
     */
    const val RESULT_REFUSED = 1
    const val MAX_REFUSAL_CHARS = 200

    /** Longer than the system's foreground receiver timeout, so a receiver that runs out of time is never acknowledged late. */
    const val DELIVERY_WAIT_MILLIS = 12_000L

    const val MAX_LABEL_CHARS = 64
    const val MAX_DESCRIPTION_CHARS = 200

    /** The needs a [META_NEEDS] value declares; a word that is not a need is ignored, so a newer app lists one an older Gravel does not know. */
    fun parseNeeds(value: String?): Set<Need> = value.orEmpty().split(',').mapNotNullTo(mutableSetOf()) {
        when (it.trim()) {
            NEED_LOCATION -> Need.LOCATION
            NEED_RECORDED_AT -> Need.RECORDED_AT
            else -> null
        }
    }
}

/** One row of an app's router list as the provider returned it; every column is text another app wrote. */
data class ListedRow(val id: String?, val label: String?, val description: String?, val needs: String?)

/** A listed router once its row passed [ListedRows.read]. */
data class ListedEntry(val id: String, val label: String, val description: String?, val needs: Set<Need>)

object ListedRows {
    /**
     * The rows that name a router: an id of the router id grammar not taken by an earlier row or
     * by one of [declared] (the receiver's own id wins), a label that is not empty once cleaned;
     * the first [RouterApi.MAX_LISTED] of them, labels and descriptions bounded and on one line.
     */
    fun read(rows: List<ListedRow>, declared: Set<String>): List<ListedEntry> {
        val taken = declared.toMutableSet()
        return rows.asSequence().mapNotNull { row ->
            val label = clean(row.label, RouterApi.MAX_LABEL_CHARS) ?: return@mapNotNull null
            val id = row.id?.takeIf { RoutingConfig.isRouterId(it) && taken.add(it) } ?: return@mapNotNull null
            ListedEntry(id, label, clean(row.description, RouterApi.MAX_DESCRIPTION_CHARS), RouterApi.parseNeeds(row.needs))
        }.take(RouterApi.MAX_LISTED).toList()
    }

    fun clean(value: String?, max: Int): String? = value?.filterNot { it.isISOControl() }?.trim()?.take(max)?.ifEmpty { null }
}

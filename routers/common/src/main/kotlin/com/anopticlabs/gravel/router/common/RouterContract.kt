package com.anopticlabs.gravel.router.common

/**
 * The names of Gravel's router API, copied from `ROUTER_API.md` and `RouterApi` in Gravel's
 * `:notebook` module, which an app of its own cannot depend on. `RouterContractTest` holds them
 * against the document, so a drift is caught here.
 */
object RouterContract {
    const val API_VERSION = 1

    /** The version of a delivery to a listed router, which names it in [EXTRA_ROUTER_ID]. */
    const val API_VERSION_LISTED = 2
    const val EXTRA_ROUTER_ID = "router_id"

    /** The receiver's meta-data naming the authority of the app's [RouterListProvider], and the provider's one path and columns. */
    const val META_LIST = "com.anopticlabs.gravel.notebook.router.list"
    const val LIST_PATH = "routers"
    const val COLUMN_ID = "id"
    const val COLUMN_LABEL = "label"
    const val COLUMN_DESCRIPTION = "description"
    const val COLUMN_NEEDS = "needs"
    const val ACTION_ROUTE_TEXT = "com.anopticlabs.gravel.notebook.action.ROUTE_TEXT"
    const val PERMISSION_ROUTE_TEXT = "com.anopticlabs.gravel.permission.ROUTE_NOTEBOOK_TEXT"

    const val EXTRA_API_VERSION = "api_version"
    const val EXTRA_CAPTURE_ID = "capture_id"
    const val EXTRA_TEXT = "text"
    const val EXTRA_RECORDED_AT = "recorded_at"
    const val EXTRA_LOCATION_LATITUDE = "location_latitude"
    const val EXTRA_LOCATION_LONGITUDE = "location_longitude"
    const val EXTRA_LOCATION_ACCURACY_METERS = "location_accuracy_m"

    /** The text once the words the user's Switch rules matched on the way are cut out, and those matches, one array entry each. */
    const val EXTRA_UNMATCHED_TEXT = "unmatched_text"
    const val EXTRA_MATCH_ROUTER_IDS = "match_router_ids"
    const val EXTRA_MATCH_ROUTER_NAMES = "match_router_names"
    const val EXTRA_MATCH_STARTS = "match_starts"
    const val EXTRA_MATCH_ENDS = "match_ends"
    const val EXTRA_MATCH_TEXTS = "match_texts"

    /** `Activity.RESULT_OK`: the delivery is taken. */
    const val RESULT_ACKNOWLEDGED = -1

    /** `Activity.RESULT_FIRST_USER`: the delivery can never be taken; the result data says why, in up to [MAX_REFUSAL_CHARS]. */
    const val RESULT_REFUSED = 1
    const val MAX_REFUSAL_CHARS = 200
}

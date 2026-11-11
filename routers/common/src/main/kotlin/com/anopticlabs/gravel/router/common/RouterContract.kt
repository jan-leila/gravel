package com.anopticlabs.gravel.router.common

/**
 * The names of Gravel's router API, copied from `ROUTER_API.md` and `RouterApi` in Gravel's
 * `:notebook` module, which an app of its own cannot depend on. `RouterContractTest` holds them
 * against the document, so a drift is caught here.
 */
object RouterContract {
    const val API_VERSION = 1
    const val ACTION_ROUTE_TEXT = "com.anopticlabs.gravel.notebook.action.ROUTE_TEXT"
    const val PERMISSION_ROUTE_TEXT = "com.anopticlabs.gravel.permission.ROUTE_NOTEBOOK_TEXT"

    const val EXTRA_API_VERSION = "api_version"
    const val EXTRA_CAPTURE_ID = "capture_id"
    const val EXTRA_TEXT = "text"
    const val EXTRA_RECORDED_AT = "recorded_at"
    const val EXTRA_LOCATION_LATITUDE = "location_latitude"
    const val EXTRA_LOCATION_LONGITUDE = "location_longitude"
    const val EXTRA_LOCATION_ACCURACY_METERS = "location_accuracy_m"

    /** `Activity.RESULT_OK`: the delivery is taken. */
    const val RESULT_ACKNOWLEDGED = -1

    /** `Activity.RESULT_FIRST_USER`: the delivery can never be taken; the result data says why, in up to [MAX_REFUSAL_CHARS]. */
    const val RESULT_REFUSED = 1
    const val MAX_REFUSAL_CHARS = 200
}

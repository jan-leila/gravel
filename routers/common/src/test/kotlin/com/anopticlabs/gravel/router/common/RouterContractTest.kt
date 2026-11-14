package com.anopticlabs.gravel.router.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The names are copied from Gravel's API document; this holds the copy against it. */
class RouterContractTest {

    private val document = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "ROUTER_API.md") }
        .first { it.isFile }
        .readText()

    @Test
    fun everyNameInTheContractIsInTheDocument() {
        val names = listOf(
            RouterContract.ACTION_ROUTE_TEXT, RouterContract.PERMISSION_ROUTE_TEXT,
            RouterContract.EXTRA_API_VERSION, RouterContract.EXTRA_CAPTURE_ID, RouterContract.EXTRA_TEXT, RouterContract.EXTRA_RECORDED_AT,
            RouterContract.EXTRA_LOCATION_LATITUDE, RouterContract.EXTRA_LOCATION_LONGITUDE, RouterContract.EXTRA_LOCATION_ACCURACY_METERS,
            "com.anopticlabs.gravel.notebook.router.id", "com.anopticlabs.gravel.notebook.router.needs",
            RouterContract.EXTRA_ROUTER_ID, RouterContract.META_LIST, RouterContract.LIST_PATH,
            RouterContract.COLUMN_ID, RouterContract.COLUMN_LABEL, RouterContract.COLUMN_DESCRIPTION, RouterContract.COLUMN_NEEDS,
            RouterContract.EXTRA_UNMATCHED_TEXT, RouterContract.EXTRA_FIELD_NAMES, RouterContract.EXTRA_FIELD_VALUES, RouterContract.META_PARAMS,
        )
        for (name in names) assertTrue(document.contains("`$name`") || document.contains("\"$name\""), name)
        assertTrue(document.contains("`Activity.RESULT_FIRST_USER`\n(`${RouterContract.RESULT_REFUSED}`)"))
        assertTrue(document.contains("up to ${RouterContract.MAX_REFUSAL_CHARS} characters"))
        assertTrue(document.contains("`${RouterContract.API_VERSION_LISTED}` for a delivery to a listed router"))
    }
}

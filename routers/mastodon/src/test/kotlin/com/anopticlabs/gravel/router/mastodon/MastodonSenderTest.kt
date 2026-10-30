package com.anopticlabs.gravel.router.mastodon

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals

class MastodonSenderTest {

    @Test
    fun theRequestIsAFormPostWithTheCaptureIdAsTheIdempotencyKey() {
        val request = mastodonRequest("https://mastodon.example/", "tok", "unlisted", Delivery("c1", "hello & welcome, café", null, null))
        assertEquals("POST", request.method)
        assertEquals("https://mastodon.example/api/v1/statuses", request.url)
        assertEquals(mapOf("Authorization" to "Bearer tok", "Idempotency-Key" to "c1"), request.headers)
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        assertEquals("status=hello+%26+welcome%2C+caf%C3%A9&visibility=unlisted", request.body!!.decodeToString())
    }

    @Test
    fun theTokenAndAnUnpostableStatusAreRefusalsAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, MastodonSender.answer(HttpAnswer(200, "{}")))
        assertEquals(SendResult.Refused("Mastodon refused the token (HTTP 401)"), MastodonSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Refused("Mastodon refused the status, too long perhaps (HTTP 422)"), MastodonSender.answer(HttpAnswer(422, "")))
        assertEquals(SendResult.Retry("Mastodon answered HTTP 503"), MastodonSender.answer(HttpAnswer(503, "")))
        assertEquals("private", MastodonApp.VISIBILITIES.first(), "followers-only by default")
    }
}

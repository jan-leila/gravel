package com.anopticlabs.gravel.router.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpTest {

    @Test
    fun httpsGoesAnywhereAndCleartextOnlyToAPrivateHostWhenAllowed() {
        assertTrue(Http.allowed("https://hooks.slack.com/services/x", allowPrivateCleartext = false))
        assertFalse(Http.allowed("http://hooks.slack.com/services/x", allowPrivateCleartext = true))
        assertFalse(Http.allowed("http://192.168.1.10:8123/api", allowPrivateCleartext = false))
        assertTrue(Http.allowed("http://192.168.1.10:8123/api", allowPrivateCleartext = true))
        assertTrue(Http.allowed("http://homeassistant.local:8123/api", allowPrivateCleartext = true))
        assertFalse(Http.allowed("ftp://192.168.1.10/", allowPrivateCleartext = true))
        assertFalse(Http.allowed("not a url", allowPrivateCleartext = true))
        assertFalse(Http.allowed("https:///path", allowPrivateCleartext = true))
    }

    @Test
    fun privateHostsAreLanNamesAndTheReservedRanges() {
        for (host in listOf("localhost", "nas.local", "ha.lan", "box.home.arpa", "svc.internal", "homeassistant", "nas", "10.0.0.1", "127.0.0.1", "172.16.0.9", "172.31.255.1", "192.168.0.1", "169.254.1.1", "100.64.0.1", "100.127.255.254", "[::1]", "[fd12::1]", "[fe80::1]")) {
            assertTrue(Http.isPrivateHost(host), host)
        }
        for (host in listOf("example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "193.168.0.1", "100.63.255.255", "100.128.0.1", "[2001:db8::1]", "mylocal.com", "")) {
            assertFalse(Http.isPrivateHost(host), host)
        }
        assertTrue(Http.allowed("http://homeassistant:8123", allowPrivateCleartext = true), "a dot-less name, as Home Assistant's own fallback address")
    }

    @Test
    fun aRedirectOrAMissingApiIsAnAddressProblemAndTheRestIsNot() {
        assertEquals("Slack redirected the request (HTTP 301); check the address", HttpAnswer(301, "").addressReason("Slack"))
        assertEquals("Slack has no such API at that address (HTTP 404)", HttpAnswer(404, "").addressReason("Slack"))
        assertEquals("Slack has no such API at that address (HTTP 405)", HttpAnswer(405, "").addressReason("Slack"))
        for (status in listOf(200, 400, 401, 429, 500, 503)) assertEquals(null, HttpAnswer(status, "").addressReason("Slack"), "$status")
    }

    @Test
    fun aJsonStringIsQuotedAndEscaped() {
        assertEquals("\"plain\"", jsonString("plain"))
        assertEquals("\"a \\\"quote\\\" and a \\\\ slash\"", jsonString("a \"quote\" and a \\ slash"))
        assertEquals("\"line\\nbreak\\ttab\\u0001\"", jsonString("line\nbreak\ttab\u0001"))
        assertEquals("\"café 😀\"", jsonString("café 😀"), "non-ASCII goes through as is")
    }
}

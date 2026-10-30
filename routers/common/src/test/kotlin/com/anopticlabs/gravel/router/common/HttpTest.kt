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
        for (host in listOf("localhost", "nas.local", "ha.lan", "box.home.arpa", "svc.internal", "10.0.0.1", "127.0.0.1", "172.16.0.9", "172.31.255.1", "192.168.0.1", "169.254.1.1", "[::1]", "[fd12::1]", "[fe80::1]")) {
            assertTrue(Http.isPrivateHost(host), host)
        }
        for (host in listOf("example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "193.168.0.1", "[2001:db8::1]", "local", "mylocal.com")) {
            assertFalse(Http.isPrivateHost(host), host)
        }
    }

    @Test
    fun aJsonStringIsQuotedAndEscaped() {
        assertEquals("\"plain\"", jsonString("plain"))
        assertEquals("\"a \\\"quote\\\" and a \\\\ slash\"", jsonString("a \"quote\" and a \\ slash"))
        assertEquals("\"line\\nbreak\\ttab\\u0001\"", jsonString("line\nbreak\ttab\u0001"))
        assertEquals("\"café 😀\"", jsonString("café 😀"), "non-ASCII goes through as is")
    }
}

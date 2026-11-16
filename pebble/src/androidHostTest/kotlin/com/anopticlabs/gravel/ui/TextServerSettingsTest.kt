package com.anopticlabs.gravel.ui

import com.anopticlabs.gravel.textserver.TextServerConfig
import com.anopticlabs.gravel.textserver.TextServerMode
import com.anopticlabs.gravel.textserver.TextServerStore
import com.anopticlabs.gravel.textserver.TextServerTestResult
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import coredevices.pebble.ui.dialogRequestToken
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import coredevices.util.transcription.SelfHostedServerStore
import coredevices.util.transcription.serverHostPort
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text server settings' pure decisions: the test's words, the modes offered, and what Save writes. */
class TextServerSettingsTest {

    private class RecordingSettings(private val inner: MapSettings = MapSettings()) : Settings by inner {
        val writes = mutableListOf<String>()

        override fun putString(key: String, value: String) {
            writes += key
            inner.putString(key, value)
        }

        override fun remove(key: String) {
            writes += key
            inner.remove(key)
        }
    }

    private object PlainCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): DecryptResult = DecryptResult.Success(stored.removePrefix("enc:"))
    }

    private fun holder(settings: Settings, text: TextServerConfig = TextServerConfig()) =
        CoreConfigHolder(defaultValue = CoreConfig(textServerConfig = text), settings = settings, json = Json { ignoreUnknownKeys = true })

    @Test
    fun theTestNamesWhatTheServerAnswered() {
        assertEquals("Server answered. The Notebook can use it.", textServerTestText(TextServerTestResult(200, chatReply = true)))
        assertTrue(textServerTestText(TextServerTestResult(200, chatReply = false)).contains("/v1/chat/completions"))
        assertTrue(textServerTestText(TextServerTestResult(401, chatReply = false)).contains("token"))
        assertTrue(textServerTestText(TextServerTestResult(403, chatReply = false)).contains("token"))
        assertTrue(textServerTestText(TextServerTestResult(404, chatReply = false)).contains("model"))
        assertTrue(textServerTestText(TextServerTestResult(400, chatReply = false)).contains("model name"))
        assertEquals("Server returned HTTP 502.", textServerTestText(TextServerTestResult(502, chatReply = false)))
    }

    @Test
    fun theServerModesAreOfferedOnlyWithAServer() {
        assertEquals(listOf(TextServerMode.LocalOnly), textServerModes(serverConfigured = false))
        assertEquals(TextServerMode.entries, textServerModes(serverConfigured = true))
    }

    @Test
    fun saveWritesTheTokenBeforeTheConfigAndKeepsTheMode() {
        val settings = RecordingSettings()
        val holder = holder(settings, TextServerConfig(mode = TextServerMode.ServerFirst))
        val store = TextServerStore(settings, PlainCipher)
        val url = "https://inference.example.net/v1/chat/completions"
        settings.writes.clear()
        saveTextServerSettings(holder, store, " $url ", " llama3.1:8b ", "tok", clearToken = false, savedHostPort = null)
        val writes = settings.writes.toList()
        assertTrue(writes.indexOf("text_server_token_bound") in 0 until writes.indexOf("coreapp.config"), "$writes")
        val saved = holder.config.value.textServerConfig
        assertEquals(TextServerConfig(TextServerMode.ServerFirst, url, "llama3.1:8b"), saved)
        assertEquals("tok", store.tokenFor(serverHostPort(url)))
        assertNull(holder.config.value.sttConfig.serverUrl, "the transcription server is left alone")
    }

    @Test
    fun removingTheServerReturnsToTheLocalModeAndDropsItsTokenAndPin() {
        val settings = MapSettings()
        val url = "https://inference.example.net/v1/chat/completions"
        val holder = holder(settings, TextServerConfig(TextServerMode.ServerOnly, url, "m", serverOnMobileData = true))
        val store = TextServerStore(settings, PlainCipher)
        val hp = serverHostPort(url)!!
        store.setToken(hp, "tok")
        store.trust(hp, "AB:CD")
        val speech = SelfHostedServerStore(settings, PlainCipher).also { it.trust(hp, "AB:CD") }
        saveTextServerSettings(holder, store, "", "m", "", clearToken = false, savedHostPort = hp)
        val saved = holder.config.value.textServerConfig
        assertEquals(TextServerMode.LocalOnly, saved.mode)
        assertNull(saved.serverUrl)
        assertNull(store.storedToken())
        assertNull(store.pinnedFingerprint(hp))
        assertEquals("AB:CD", speech.pinnedFingerprint(hp), "the transcription server's pin for the same address stays")
    }

    @Test
    fun theDialogsTokenComesFromTheTextServersStore() {
        val store = TextServerStore(MapSettings(), PlainCipher)
        val hp = "inference.example.net:443"
        store.setToken(hp, "tok")
        assertEquals("tok", dialogRequestToken(store, "", clearToken = false, savedHostPort = hp, hostPort = hp))
        assertNull(dialogRequestToken(store, "", clearToken = false, savedHostPort = hp, hostPort = "other.example.net:443"))
    }

    /** The URL names the user's own server and the config is logged on every change. */
    @Test
    fun theConfigPrintsWithoutTheUrl() {
        val printed = CoreConfig(textServerConfig = TextServerConfig(TextServerMode.ServerOnly, "https://inference.example.net/v1/chat/completions", "m")).toString()
        assertTrue("example.net" !in printed, printed)
        assertTrue("serverUrl=[set]" in printed, printed)
    }
}

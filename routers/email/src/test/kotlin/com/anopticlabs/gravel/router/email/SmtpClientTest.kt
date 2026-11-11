package com.anopticlabs.gravel.router.email

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class SmtpClientTest {

    /** A server that speaks the replies it is given, in order, and keeps what the client sent. */
    private class FakeServer(private val replies: Map<String, String>, private val authReply: String = "235 ok") : AutoCloseable {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val received = mutableListOf<String>()
        val data = StringBuilder()
        private val executor = Executors.newSingleThreadExecutor()

        init {
            executor.submit {
                socket.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                    val writer = PrintWriter(client.getOutputStream(), true)
                    writer.print("220 fake ready\r\n")
                    writer.flush()
                    var inData = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (inData) {
                            if (line == ".") {
                                inData = false
                                writer.print("250 queued\r\n")
                                writer.flush()
                            } else {
                                data.append(line).append("\r\n")
                            }
                            continue
                        }
                        received += line
                        val verb = line.substringBefore(' ')
                        val reply = when {
                            verb == "EHLO" -> "250-fake\r\n250 AUTH PLAIN LOGIN"
                            verb == "AUTH" -> authReply
                            verb == "DATA" -> "354 go".also { inData = true }
                            verb == "QUIT" -> "221 bye"
                            else -> replies[verb] ?: "250 ok"
                        }
                        writer.print("$reply\r\n")
                        writer.flush()
                        if (verb == "QUIT") break
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }

    private val message = MailMessage.build("me@example.net", "you@example.net", "Voice note", "hello\r\n.hidden\r\nbye", "c1", java.time.ZonedDateTime.parse("2026-09-22T17:00:00+02:00"))

    @Test
    fun aMessageGoesThroughTheWholeExchangeWithItsDotsStuffed() {
        FakeServer(emptyMap()).use { server ->
            SmtpClient("127.0.0.1", server.port, SmtpSecurity.PLAIN).send("user", "pass", "me@example.net", "you@example.net", message)
            Thread.sleep(50)
            assertEquals(listOf("EHLO gravel.router", "AUTH PLAIN " + Base64.getEncoder().encodeToString("\u0000user\u0000pass".encodeToByteArray()), "MAIL FROM:<me@example.net>", "RCPT TO:<you@example.net>", "DATA", "QUIT"), server.received)
            assertTrue(server.data.toString().contains("Content-Transfer-Encoding: base64\r\n"), server.data.toString())
        }
    }

    @Test
    fun aServerWithoutAuthPlainGetsAuthLogin() {
        FakeServer(emptyMap(), authReply = "504 no").use { server ->
            // The fake answers every AUTH line with 504; LOGIN's steps are then refused, which ends the send as refused.
            assertFailsWith<SmtpRefusedException> { SmtpClient("127.0.0.1", server.port, SmtpSecurity.PLAIN).send("user", "pass", "a@b", "c@d", message) }
            Thread.sleep(50)
            assertEquals("AUTH LOGIN", server.received[2])
        }
    }

    @Test
    fun aFiveHundredIsARefusalAndAFourHundredARetry() {
        FakeServer(mapOf("RCPT" to "550 no such user")).use { server ->
            val refused = assertFailsWith<SmtpRefusedException> { SmtpClient("127.0.0.1", server.port, SmtpSecurity.PLAIN).send("u", "p", "a@b", "c@d", message) }
            assertTrue(refused.message!!.contains("550 no such user"))
        }
        FakeServer(mapOf("MAIL" to "451 try later")).use { server ->
            val retry = try {
                SmtpClient("127.0.0.1", server.port, SmtpSecurity.PLAIN).send("u", "p", "a@b", "c@d", message)
                fail("expected a failure")
            } catch (e: SmtpRefusedException) {
                fail("a 4xx is not a refusal")
            } catch (e: java.io.IOException) {
                e
            }
            assertTrue(retry.message!!.contains("451"))
        }
    }

    @Test
    fun dotStuffingDoublesALeadingDotOnAnyLine() {
        assertEquals("a\r\n..b\r\n...\r\nc", SmtpClient.dotStuffed("a\r\n.b\r\n..\r\nc".encodeToByteArray()).decodeToString())
    }
}

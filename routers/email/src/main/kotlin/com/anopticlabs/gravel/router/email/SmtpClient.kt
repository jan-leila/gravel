package com.anopticlabs.gravel.router.email

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** How the connection is secured: TLS from the first byte, or STARTTLS after the greeting. [PLAIN] exists for the tests' fake server only. */
enum class SmtpSecurity { TLS, STARTTLS, PLAIN }

/** A reply the server gave with a code that ends the send for good, as opposed to one worth another try. */
class SmtpRefusedException(message: String) : IOException(message)

/**
 * The little of SMTP a one-recipient text message needs: EHLO, TLS, AUTH PLAIN (LOGIN as a
 * fallback), MAIL FROM, RCPT TO, DATA, QUIT, with the replies checked at each step. A 5xx reply
 * is a [SmtpRefusedException], since the same message would get it again; a 4xx or a broken
 * connection is an [IOException] to retry. [pinnedSha256] accepts the server's certificate by
 * its digest instead of the system's trust, for a Proton Bridge or another server with a
 * self-signed certificate; without it the system's trust and the host name apply.
 */
open class SmtpClient(
    private val host: String,
    private val port: Int,
    private val security: SmtpSecurity,
    private val pinnedSha256: String? = null,
    private val timeoutMillis: Int = 30_000,
) {
    @Throws(IOException::class)
    open fun send(username: String, password: String, from: String, to: String, message: ByteArray) {
        // Not Socket.apply: inside it, `port` would be the socket's own, still zero.
        var socket: Socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        try {
            if (security == SmtpSecurity.TLS) socket = secure(socket)
            var session = Session(socket)
            session.expect(220)
            session.command("EHLO gravel.router", 250)
            if (security == SmtpSecurity.STARTTLS) {
                session.command("STARTTLS", 220)
                socket = secure(socket)
                session = Session(socket)
                session.command("EHLO gravel.router", 250)
            }
            authenticate(session, username, password)
            session.command("MAIL FROM:<$from>", 250)
            session.command("RCPT TO:<$to>", 250, 251)
            session.command("DATA", 354)
            session.write(dotStuffed(message))
            session.write("\r\n.\r\n".encodeToByteArray())
            session.expect(250)
            try {
                session.command("QUIT", 221)
            } catch (_: IOException) {
                // The message was taken at the 250 above; a server that hangs up without a 221 changes nothing.
            }
        } finally {
            socket.close()
        }
    }

    private fun authenticate(session: Session, username: String, password: String) {
        val plain = Base64.getEncoder().encodeToString("\u0000$username\u0000$password".encodeToByteArray())
        val reply = session.command("AUTH PLAIN $plain", 235, 504, 535, 500, 502, refusedOn5xx = false)
        if (reply.code == 235) return
        session.command("AUTH LOGIN", 334)
        session.command(Base64.getEncoder().encodeToString(username.encodeToByteArray()), 334)
        session.command(Base64.getEncoder().encodeToString(password.encodeToByteArray()), 235)
    }

    private fun secure(socket: Socket): Socket {
        val factory: SSLSocketFactory = if (pinnedSha256 == null) {
            SSLSocketFactory.getDefault() as SSLSocketFactory
        } else {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pinnedSha256)), null) }.socketFactory
        }
        val tls = factory.createSocket(socket, host, port, true) as SSLSocket
        if (pinnedSha256 == null) {
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
        tls.startHandshake()
        return tls
    }

    private class Reply(val code: Int, val text: String)

    private class Session(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        private val out: OutputStream = socket.getOutputStream()

        fun write(bytes: ByteArray) {
            out.write(bytes)
            out.flush()
        }

        /** Sends [line] and expects one of [accepted]; a 5xx outside them refuses, anything else outside them is a retry. */
        fun command(line: String, vararg accepted: Int, refusedOn5xx: Boolean = true): Reply {
            write("$line\r\n".encodeToByteArray())
            return expect(*accepted, refusedOn5xx = refusedOn5xx)
        }

        fun expect(vararg accepted: Int, refusedOn5xx: Boolean = true): Reply {
            val reply = read()
            if (reply.code in accepted) return reply
            if (refusedOn5xx && reply.code >= 500) throw SmtpRefusedException("the server answered ${reply.code} ${reply.text}".trim())
            throw IOException("the server answered ${reply.code} ${reply.text}".trim())
        }

        // Multi-line replies continue with "250-"; the last line has a space after the code.
        private fun read(): Reply {
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: throw IOException("the server closed the connection")
                lines += line
                if (line.length < 4 || line[3] != '-') break
                if (lines.size > 64) throw IOException("the server's reply does not end")
            }
            val last = lines.last()
            val code = last.take(3).toIntOrNull() ?: throw IOException("the server's reply has no code")
            return Reply(code, last.drop(4).take(200))
        }
    }

    /** Accepts exactly the certificate whose SHA-256 is pinned, so a self-signed server is as safe as the pin. */
    private class PinnedTrust(private val sha256: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("not for clients")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException("no certificate")
            if (digest(leaf) != sha256.lowercase()) throw java.security.cert.CertificateException("the server's certificate is not the pinned one")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** The lowercase hex SHA-256 of a certificate's DER bytes, as `openssl x509 -fingerprint -sha256` prints it without the colons. */
        fun digest(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

        /** A line starting with a dot gets a second one, so the message's own ".\r\n" cannot end the data (RFC 5321, 4.5.2). */
        fun dotStuffed(message: ByteArray): ByteArray {
            val text = message.decodeToString()
            val stuffed = text.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            return stuffed.encodeToByteArray()
        }
    }
}

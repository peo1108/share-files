package com.sharefiles.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransfersTest {
    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var fingerprint: String

    @Before
    fun setUp() {
        certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(InetAddress.getLoopbackAddress().canonicalHostName)
            .build()
        fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.certificate.encoded)
            .joinToString("") { "%02x".format(it) }

        val serverCerts = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api(pin: String = fingerprint) =
        ShareApi(pinnedClient(pin), "https://${server.hostName}:${server.port}", "token")

    private class Source(private val data: ByteArray) : UploadSource {
        val openedAt = mutableListOf<Long>()
        override val name = "a.bin"
        override val size = data.size.toLong()
        override val modifiedAt: String? = null
        override fun openAt(offset: Long): InputStream {
            openedAt += offset
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
    }

    private class Sink(initial: ByteArray = ByteArray(0)) : DownloadSink {
        val stored = ByteArrayOutputStream().apply { write(initial) }
        var committed = false
        var resets = 0
        override fun length() = stored.size().toLong()
        override fun reset() {
            resets++
            stored.reset()
        }
        override fun openForAppend(): OutputStream = stored
        override fun commit() {
            committed = true
        }
    }

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse(
        code = code,
        headers = headersOf("Content-Type", "application/json"),
        body = body,
    )

    @Test
    fun upload_resumesFromServerOffset() = runTest {
        val data = ByteArray(5000) { it.toByte() }
        val received = Buffer()
        var patchOffset: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.method) {
                "POST" -> jsonResponse("""{"id":"u1","offset":3000,"completed":false,"savedName":null}""")
                else -> {
                    patchOffset = request.headers["Upload-Offset"]
                    request.body?.let { received.write(it) }
                    jsonResponse("""{"id":"u1","offset":5000,"completed":true,"savedName":"a.bin"}""")
                }
            }
        }
        val source = Source(data)

        val saved = Transfers(api()).upload("key", source) {}

        assertEquals("a.bin", saved)
        assertEquals("3000", patchOffset)
        assertEquals(listOf(3000L), source.openedAt)
        assertArrayEquals(data.copyOfRange(3000, 5000), received.readByteArray())
    }

    @Test
    fun upload_alreadyCompletedSendsNothing() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                jsonResponse("""{"id":"u1","offset":10,"completed":true,"savedName":"a (1).bin"}""")
        }
        val source = Source(ByteArray(10))

        val saved = Transfers(api()).upload("key", source) {}

        assertEquals("a (1).bin", saved)
        assertTrue(source.openedAt.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun upload_followsServerOffsetOnConflict() = runTest {
        val data = ByteArray(1000) { it.toByte() }
        val offsets = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    return jsonResponse("""{"id":"u1","offset":100,"completed":false,"savedName":null}""")
                }
                offsets += request.headers["Upload-Offset"]
                return if (offsets.size == 1) {
                    jsonResponse("""{"code":"offset_mismatch","message":"x","offset":400}""", 409)
                } else {
                    jsonResponse("""{"id":"u1","offset":1000,"completed":true,"savedName":"a.bin"}""")
                }
            }
        }

        Transfers(api()).upload("key", Source(data)) {}

        assertEquals(listOf<String?>("100", "400"), offsets)
    }

    @Test
    fun download_resumesWithRange() = runTest {
        val data = ByteArray(4000) { it.toByte() }
        var range: String? = null
        var ifRange: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                range = request.headers["Range"]
                ifRange = request.headers["If-Range"]
                return MockResponse.Builder()
                    .code(206)
                    .headers(headersOf("ETag", "\"v1\""))
                    .body(Buffer().write(data, 1500, 2500))
                    .build()
            }
        }
        val sink = Sink(data.copyOfRange(0, 1500))

        Transfers(api()).download("/v1/outbox/x/content", data.size.toLong(), "\"v1\"", sink, {}) {}

        assertEquals("bytes=1500-", range)
        assertEquals("\"v1\"", ifRange)
        assertArrayEquals(data, sink.stored.toByteArray())
        assertTrue(sink.committed)
        assertEquals(0, sink.resets)
    }

    @Test
    fun download_restartsWhenServerIgnoresRange() = runTest {
        val data = ByteArray(2000) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(200).body(Buffer().write(data)).build()
        }
        val sink = Sink(ByteArray(500) { 7 })

        Transfers(api()).download("/x", data.size.toLong(), "\"changed\"", sink, {}) {}

        assertEquals(1, sink.resets)
        assertArrayEquals(data, sink.stored.toByteArray())
        assertTrue(sink.committed)
    }

    @Test
    fun download_shortBodyIsNotCommitted() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(200).body(Buffer().write(ByteArray(100))).build()
        }
        val sink = Sink()

        var failure: Throwable? = null
        try {
            Transfers(api()).download("/x", 200, null, sink, {}) {}
        } catch (e: IOException) {
            failure = e
        }

        assertTrue(failure is IOException)
        assertEquals(false, sink.committed)
    }

    @Test
    fun apiErrors_carryCodeAndStatus() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                jsonResponse("""{"code":"insufficient_storage","message":"PC đã hết dung lượng"}""", 507)
        }

        var failure: ApiFailure? = null
        try {
            api().beginUpload("k", "a", 1, null)
        } catch (e: ApiFailure) {
            failure = e
        }

        assertEquals(507, failure?.status)
        assertEquals("insufficient_storage", failure?.code)
        assertEquals("PC đã hết dung lượng", failure?.message)
    }

    @Test
    fun downloadPath_encodesReservedCharacters() {
        assertEquals(
            "/v1/download?root=r1&path=a%2Bb%20c%26d%3Df.txt",
            ShareApi.downloadPath("r1", "a+b c&d=f.txt"),
        )
    }

    @Test
    fun pinning_rejectsWrongFingerprint() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                jsonResponse("""{"id":"s","name":"n","apiVersion":1}""")
        }

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { api(pin = "0".repeat(64)).info() }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pinning_acceptsRightFingerprintWithoutCaOrHostname() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                jsonResponse("""{"id":"s","name":"n","apiVersion":1}""")
        }

        assertEquals("s", api().info().id)
    }
}

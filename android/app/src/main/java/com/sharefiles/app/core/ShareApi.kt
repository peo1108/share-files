package com.sharefiles.app.core

import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The server answered with an error from the protocol's error table. */
class ApiFailure(val status: Int, val code: String, message: String, val offset: Long? = null) : Exception(message)

internal val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private val JsonType = "application/json".toMediaType()
internal val BinaryType = "application/octet-stream".toMediaType()

/**
 * An HTTPS client that trusts exactly one certificate. There is no CA or hostname check because
 * the fingerprint from the pairing QR code is a stronger proof of identity on a home network.
 */
fun pinnedClient(fingerprint: String): OkHttpClient {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not used")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHex()
            if (actual != fingerprint) throw CertificateException("Certificate does not match the paired PC")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }

    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class ShareApi(
    private val client: OkHttpClient,
    val baseUrl: String,
    private val token: String?,
) {
    fun withToken(newToken: String) = ShareApi(client, baseUrl, newToken)

    suspend fun info(): ServerInfo = getJson("/v1/info")

    suspend fun pair(secret: String, deviceId: String, deviceName: String): PairResponse =
        postJson("/v1/pair", PairRequest(secret, deviceId, deviceName, "android"))

    suspend fun roots(): List<RootDto> = getJson("/v1/roots")

    suspend fun browse(root: String, path: String): BrowseResponse =
        getJson("/v1/browse?root=${encode(root)}&path=${encode(path)}")

    suspend fun outbox(): List<OutboxEntry> = getJson("/v1/outbox")

    suspend fun ackOutbox(id: String) = send(request("/v1/outbox/${encode(id)}/ack").post(ByteArray(0).toRequestBody()))

    suspend fun rejectOutbox(id: String) = send(request("/v1/outbox/${encode(id)}").delete())

    suspend fun beginUpload(fileKey: String, name: String, size: Long, modifiedAt: String?): UploadStatus =
        postJson("/v1/uploads", BeginUpload(fileKey, name, size, modifiedAt))

    suspend fun patchUpload(id: String, offset: Long, body: RequestBody): UploadStatus {
        val request = request("/v1/uploads/${encode(id)}")
            .header("Upload-Offset", offset.toString())
            .patch(body)
        return execute(request) { ApiJson.decodeFromString(it.body.string()) }
    }

    suspend fun deleteUpload(id: String) = send(request("/v1/uploads/${encode(id)}").delete())

    /** Runs [handle] on the response body. A non-success status becomes an [ApiFailure]. */
    suspend fun <T> stream(path: String, headers: Map<String, String> = emptyMap(), handle: suspend (Response) -> T): T {
        val builder = request(path)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return execute(builder, handle)
    }

    suspend fun postRaw(path: String, body: RequestBody) = send(request(path).post(body))

    private suspend inline fun <reified T> getJson(path: String): T =
        execute(request(path)) { ApiJson.decodeFromString<T>(it.body.string()) }

    private suspend inline fun <reified B, reified T> postJson(path: String, body: B): T {
        val payload = ApiJson.encodeToString(body).toRequestBody(JsonType)
        return execute(request(path).post(payload)) { ApiJson.decodeFromString<T>(it.body.string()) }
    }

    private suspend fun send(builder: Request.Builder) = execute(builder) { }

    private fun request(path: String): Request.Builder {
        val builder = Request.Builder().url(baseUrl + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    /** Blocking OkHttp call that a cancelled coroutine can abort. */
    private suspend fun <T> execute(builder: Request.Builder, handle: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val call = client.newCall(builder.build())
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw toFailure(response)
                    handle(response)
                }
            } finally {
                watcher.cancel()
            }
        }

    private fun toFailure(response: Response): ApiFailure {
        val body = try {
            ApiJson.decodeFromString<ApiErrorBody>(response.body.string())
        } catch (_: IOException) {
            ApiErrorBody()
        } catch (_: IllegalArgumentException) {
            ApiErrorBody()
        }
        return ApiFailure(response.code, body.code, body.message.ifEmpty { "Lỗi ${response.code}" }, body.offset)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    @kotlinx.serialization.Serializable
    private data class PairRequest(val secret: String, val deviceId: String, val deviceName: String, val platform: String)

    @kotlinx.serialization.Serializable
    private data class BeginUpload(val fileKey: String, val name: String, val size: Long, val modifiedAt: String?)

    companion object {
        fun downloadPath(root: String, path: String): String {
            fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8.name()).replace("+", "%20")
            return "/v1/download?root=${enc(root)}&path=${enc(path)}"
        }

        fun outboxContentPath(id: String) = "/v1/outbox/$id/content"
    }
}

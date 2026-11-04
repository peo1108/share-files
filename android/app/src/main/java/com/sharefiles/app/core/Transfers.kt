package com.sharefiles.app.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import kotlin.coroutines.coroutineContext

/** Where upload bytes come from. Implemented over a content URI in the app, and in memory in tests. */
interface UploadSource {
    val name: String
    val size: Long
    val modifiedAt: String?
    fun openAt(offset: Long): InputStream
}

/** Where download bytes go. Whatever is already stored is what a resumed download continues from. */
interface DownloadSink {
    fun length(): Long
    fun reset()
    fun openForAppend(): OutputStream
    fun commit()
}

internal const val BUFFER_SIZE = 1 shl 20

/**
 * One transfer attempt. A dropped connection surfaces as an IOException and the caller simply runs
 * the same call again: the offset always comes from the side that holds the data.
 */
class Transfers(private val api: ShareApi) {

    /** Returns the name the file was saved under on the PC. */
    suspend fun upload(fileKey: String, source: UploadSource, onProgress: (Long) -> Unit): String {
        var status = api.beginUpload(fileKey, source.name, source.size, source.modifiedAt)
        onProgress(status.offset)
        while (!status.completed) {
            status = try {
                api.patchUpload(status.id, status.offset, UploadBody(source, status.offset, onProgress))
            } catch (e: ApiFailure) {
                val correct = e.offset
                if (e.code != "offset_mismatch" || correct == null) throw e
                onProgress(correct)
                status.copy(offset = correct)
            }
            if (!status.completed && status.offset >= source.size) {
                throw IOException("Server did not finish the upload")
            }
        }
        onProgress(source.size)
        return status.savedName ?: source.name
    }

    /** Fetches [path] into [sink]. [onEtag] reports the validator so a later attempt can use If-Range. */
    suspend fun download(
        path: String,
        size: Long,
        etag: String?,
        sink: DownloadSink,
        onEtag: (String) -> Unit,
        onProgress: (Long) -> Unit,
    ) {
        val start = sink.length()
        if (size > 0 && start == size) {
            // All bytes arrived in an earlier attempt; only the finishing step was lost.
            onProgress(size)
            sink.commit()
            return
        }

        val headers = buildMap {
            if (start > 0) {
                put("Range", "bytes=$start-")
                etag?.let { put("If-Range", it) }
            }
        }

        api.stream(path, headers) { response ->
            response.header("ETag")?.let(onEtag)

            var position = start
            if (response.code != 206) {
                // Fresh download, or the file changed on the PC and the old bytes are worthless.
                if (start > 0) sink.reset()
                position = 0
            }
            onProgress(position)

            val input = response.body.byteStream()
            sink.openForAppend().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    position += read
                    onProgress(position)
                }
                output.flush()
            }

            if (position != size) throw IOException("Connection ended at $position of $size bytes")
        }
        sink.commit()
    }
}

private class UploadBody(
    private val source: UploadSource,
    private val offset: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = BinaryType
    override fun contentLength(): Long = source.size - offset

    override fun writeTo(sink: BufferedSink) {
        source.openAt(offset).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var position = offset
            while (position < source.size) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), source.size - position).toInt())
                if (read < 0) throw IOException("File ended earlier than expected")
                sink.write(buffer, 0, read)
                position += read
                onProgress(position)
            }
        }
    }
}

/** Smoothed transfer speed, safe to feed a counter that jumps when a transfer resumes. */
class SpeedMeter {
    private var lastBytes = -1L
    private var lastSeconds = 0.0
    private var speed = 0.0

    fun sample(bytes: Long, seconds: Double): Double {
        if (lastBytes >= 0 && seconds > lastSeconds) {
            val current = maxOf(0L, bytes - lastBytes) / (seconds - lastSeconds)
            speed = if (speed == 0.0) current else speed * 0.6 + current * 0.4
        }
        if (bytes < lastBytes) speed = 0.0
        lastBytes = bytes
        lastSeconds = seconds
        return speed
    }
}

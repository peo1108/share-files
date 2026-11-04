package com.sharefiles.app.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
    fun openForWrite(): PositionalWriter
    fun commit()
}

/** Writes at any position. Safe to call from several threads at once. */
interface PositionalWriter : Closeable {
    fun write(position: Long, buffer: ByteArray, length: Int)
}

internal const val BUFFER_SIZE = 1 shl 20

/** Large files are fetched as this many ranges at once, because one TCP stream does not fill fast Wi-Fi. */
const val DOWNLOAD_PARTS = 4
const val PARALLEL_MIN_BYTES = 32L * 1024 * 1024
private const val PARTS_SAVE_MILLIS = 2000L

/** Splits [size] bytes into [count] contiguous ranges. */
fun splitRanges(size: Long, count: Int): List<LongRange> =
    (0 until count).map { i -> (size * i / count) until (size * (i + 1) / count) }

private val LongRange.length get() = last - first + 1

/** The file on the PC is no longer the one the bytes already held came from. */
private class SourceChanged(val etag: String?, val length: Long?) : IOException("File trên PC đã thay đổi")

/**
 * One transfer attempt. A dropped connection surfaces as an IOException and the caller simply runs
 * the same call again: the offset always comes from the side that holds the data.
 */
class Transfers(private val api: ShareApi, private val parallelMinBytes: Long = PARALLEL_MIN_BYTES) {

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

    /**
     * Fetches [path] into [sink]. [onEtag] reports the validator so a later attempt can use If-Range.
     * Large files are fetched in [DOWNLOAD_PARTS] ranges at once; [parts] and [onParts] carry how far
     * each range got, so a later attempt continues every range where it stopped.
     */
    suspend fun download(
        path: String,
        size: Long,
        etag: String?,
        sink: DownloadSink,
        onEtag: (String) -> Unit,
        parts: List<Long>? = null,
        onParts: (List<Long>) -> Unit = {},
        onProgress: (Long) -> Unit,
    ) {
        // A download begun in one stream, by this version or an older one, stays in one stream.
        val parallel = parts?.size == DOWNLOAD_PARTS || (size >= parallelMinBytes && sink.length() == 0L)
        if (parallel) {
            downloadParts(path, size, etag, parts, sink, onEtag, onParts, onProgress)
        } else {
            downloadStream(path, size, etag, sink, onEtag, onProgress)
        }
    }

    private suspend fun downloadStream(
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

    private suspend fun downloadParts(
        path: String,
        size: Long,
        etag: String?,
        parts: List<Long>?,
        sink: DownloadSink,
        onEtag: (String) -> Unit,
        onParts: (List<Long>) -> Unit,
        onProgress: (Long) -> Unit,
    ) {
        val ranges = splitRanges(size, DOWNLOAD_PARTS)
        val done = AtomicLongArray(DOWNLOAD_PARTS)
        // Without a validator there is no telling whether held bytes still match the PC's file.
        if (etag != null && parts?.size == DOWNLOAD_PARTS) {
            parts.forEachIndexed { i, held -> done.set(i, held.coerceIn(0, ranges[i].length)) }
        }
        fun snapshot() = List(DOWNLOAD_PARTS) { done.get(it) }
        val total = AtomicLong(snapshot().sum())
        onProgress(total.get())

        try {
            sink.openForWrite().use { writer ->
                coroutineScope {
                    val pending = ranges.indices.filter { done.get(it) < ranges[it].length }
                    if (pending.isEmpty()) return@coroutineScope
                    val saver = launch {
                        while (true) {
                            delay(PARTS_SAVE_MILLIS)
                            onParts(snapshot())
                        }
                    }

                    // The first response names the file version; the other ranges then insist on it.
                    val validator = CompletableDeferred<String?>()
                    val first = launch {
                        fetchPart(path, size, ranges[pending[0]], pending[0], done, total, writer, etag, onProgress) { tag ->
                            tag?.let(onEtag)
                            validator.complete(tag)
                        }
                    }
                    val known = etag ?: validator.await()
                    val rest = pending.drop(1).map { i ->
                        launch { fetchPart(path, size, ranges[i], i, done, total, writer, known, onProgress) {} }
                    }
                    (rest + first).joinAll()
                    saver.cancel()
                }
            }
        } catch (e: SourceChanged) {
            for (i in 0 until DOWNLOAD_PARTS) done.set(i, 0)
            sink.reset()
            onProgress(0)
            if (e.length != null && e.length != size) {
                throw ApiFailure(409, "source_changed", "File trên PC đã đổi kích thước, hãy tải lại")
            }
            e.etag?.let(onEtag)
            throw IOException(e.message)
        } finally {
            onParts(snapshot())
        }
        sink.commit()
    }

    private suspend fun fetchPart(
        path: String,
        size: Long,
        range: LongRange,
        index: Int,
        done: AtomicLongArray,
        total: AtomicLong,
        writer: PositionalWriter,
        ifRange: String?,
        onProgress: (Long) -> Unit,
        onHeaders: (String?) -> Unit,
    ) {
        val start = range.first + done.get(index)
        val headers = buildMap {
            put("Range", "bytes=$start-${range.last}")
            ifRange?.let { put("If-Range", it) }
        }
        try {
            api.stream(path, headers) { response ->
                val tag = response.header("ETag")
                if (response.code != 206) throw SourceChanged(tag, response.body.contentLength().takeIf { it >= 0 })
                val contentRange = response.header("Content-Range")
                if (contentRange != "bytes $start-${range.last}/$size") {
                    throw SourceChanged(tag, contentRange?.substringAfterLast('/')?.toLongOrNull())
                }
                onHeaders(tag)

                val input = response.body.byteStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var position = start
                while (position <= range.last) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), range.last + 1 - position).toInt())
                    if (read < 0) break
                    writer.write(position, buffer, read)
                    position += read
                    done.addAndGet(index, read.toLong())
                    onProgress(total.addAndGet(read.toLong()))
                }
                if (position != range.last + 1) {
                    throw IOException("Connection ended at $position of part ending at ${range.last}")
                }
            }
        } catch (e: ApiFailure) {
            // The file shrank below this range.
            if (e.status == 416) throw SourceChanged(null, null) else throw e
        }
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

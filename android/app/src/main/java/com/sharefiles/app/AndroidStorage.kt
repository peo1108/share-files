package com.sharefiles.app

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.sharefiles.app.core.DownloadSink
import com.sharefiles.app.core.UploadSource
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/** A file picked on the device that cannot be read any more. Retrying will not help. */
class SourceUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

data class PickedFile(val uri: Uri, val name: String, val size: Long, val modifiedAt: String?)

fun ContentResolver.describe(uri: Uri): PickedFile? {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    var name: String? = null
    var size = -1L
    var modified: String? = null
    runCatching {
        query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val millis = cursor.getLong(0)
                if (millis > 0) modified = Instant.ofEpochMilli(millis).toString()
            }
        }
    }
    if (size < 0) {
        size = runCatching { openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
    }
    if (size < 0) return null
    return PickedFile(uri, name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size, modified)
}

class ContentUploadSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val modifiedAt: String?,
) : UploadSource {
    override fun openAt(offset: Long): InputStream {
        val descriptor = try {
            resolver.openAssetFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            throw SourceUnavailable("File trên máy không còn đọc được", e)
        } catch (e: SecurityException) {
            throw SourceUnavailable("Không còn quyền đọc file này", e)
        } ?: throw SourceUnavailable("File trên máy không còn đọc được")

        val stream = descriptor.createInputStream()
        var remaining = offset
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                stream.close()
                throw SourceUnavailable("File đã thay đổi, không chạy tiếp được")
            }
            remaining -= skipped
        }
        return stream
    }
}

/** Writes into Download/ShareFiles. The entry stays pending, and hidden, until the file is complete. */
class MediaStoreSink(
    private val resolver: ContentResolver,
    private val name: String,
    existing: Uri?,
    private val onCreated: (Uri) -> Unit,
) : DownloadSink {
    private var uri: Uri? = existing?.takeIf(::exists)

    private fun exists(candidate: Uri) = runCatching {
        resolver.openFileDescriptor(candidate, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun ensureEntry(): Uri {
        uri?.let { return it }
        val extension = name.substringAfterLast('.', "").lowercase()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/ShareFiles")
            put(
                MediaStore.Downloads.MIME_TYPE,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Không tạo được file trong Download")
        uri = created
        onCreated(created)
        return created
    }

    override fun length(): Long {
        val current = uri ?: return 0
        return resolver.openFileDescriptor(current, "r")?.use { it.statSize } ?: 0
    }

    override fun reset() {
        val current = uri ?: return
        resolver.openFileDescriptor(current, "rwt")?.close()
    }

    override fun openForAppend(): OutputStream {
        val current = ensureEntry()
        val descriptor = resolver.openFileDescriptor(current, "rw") ?: throw IOException("Không ghi được file")
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor) as FileOutputStream
        stream.channel.position(stream.channel.size())
        return stream
    }

    override fun commit() {
        val current = ensureEntry()
        resolver.update(current, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /** Drops the partial file when the user cancels. */
    fun discard() {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        uri = null
    }
}

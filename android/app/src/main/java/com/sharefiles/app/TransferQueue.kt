package com.sharefiles.app

import android.content.Context
import android.net.Uri
import com.sharefiles.app.core.ApiFailure
import com.sharefiles.app.core.ApiJson
import com.sharefiles.app.core.AppLog
import com.sharefiles.app.core.OutboxEntry
import com.sharefiles.app.core.ShareApi
import com.sharefiles.app.core.SpeedMeter
import com.sharefiles.app.core.Transfers
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferState { QUEUED, RUNNING, WAITING, DONE, FAILED }

@Serializable
data class TransferItem(
    val id: String,
    val direction: TransferDirection,
    val name: String,
    val size: Long,
    val state: TransferState = TransferState.QUEUED,
    val transferred: Long = 0,
    val error: String? = null,
    val sourceUri: String? = null,
    val modifiedAt: String? = null,
    val remotePath: String? = null,
    val outboxId: String? = null,
    val targetUri: String? = null,
    val etag: String? = null,
    @Transient val speed: Double = 0.0,
)

/**
 * All transfers, their persistence and their workers. A transfer that loses its connection goes to
 * WAITING and is retried with the same id, so it continues rather than starts over.
 */
class TransferQueue(
    context: Context,
    private val scope: CoroutineScope,
    private val api: () -> ShareApi?,
    private val onConnectionSuspect: () -> Unit,
) {
    private val resolver = context.contentResolver
    private val file = File(context.filesDir, "transfers.json")
    private val saveLock = Any()

    private class Running(val job: Job, val position: AtomicLong, val meter: SpeedMeter, val startedNanos: Long)

    private val running = ConcurrentHashMap<String, Running>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(load())

    val items: StateFlow<List<TransferItem>> = state

    val hasActive: StateFlow<Boolean> = state
        .map { list -> list.any { it.isActive() } }
        .stateIn(scope, SharingStarted.Eagerly, state.value.any { it.isActive() })

    init {
        scope.launch { dispatchLoop() }
        scope.launch { progressLoop() }
    }

    fun poke() {
        wake.trySend(Unit)
    }

    fun addUploads(files: List<PickedFile>) {
        val added = files.map {
            TransferItem(
                id = UUID.randomUUID().toString(),
                direction = TransferDirection.UPLOAD,
                name = it.name,
                size = it.size,
                sourceUri = it.uri.toString(),
                modifiedAt = it.modifiedAt,
            )
        }
        add(added)
    }

    fun addDownload(rootId: String, path: String, name: String, size: Long) {
        add(
            listOf(
                TransferItem(
                    id = UUID.randomUUID().toString(),
                    direction = TransferDirection.DOWNLOAD,
                    name = name,
                    size = size,
                    remotePath = ShareApi.downloadPath(rootId, path),
                ),
            ),
        )
    }

    /** Called from each outbox poll. An entry already in the list is ignored. */
    fun addOutbox(entries: List<OutboxEntry>) {
        val known = state.value.mapNotNull { it.outboxId }.toSet()
        val added = entries.filter { !it.sourceMissing && it.id !in known }.map {
            TransferItem(
                id = "outbox-${it.id}",
                direction = TransferDirection.DOWNLOAD,
                name = it.name,
                size = it.size,
                remotePath = ShareApi.outboxContentPath(it.id),
                outboxId = it.id,
            )
        }
        if (added.isNotEmpty()) add(added)
    }

    fun retry(id: String) {
        attempts.remove(id)
        retryAt.remove(id)
        change(id) { it.copy(state = TransferState.QUEUED, error = null) }
        persist()
        poke()
    }

    fun cancel(id: String) {
        val item = state.value.firstOrNull { it.id == id } ?: return
        running.remove(id)?.job?.cancel()
        state.update { list -> list.filterNot { it.id == id } }
        persist()

        scope.launch(Dispatchers.IO) {
            if (item.direction == TransferDirection.DOWNLOAD) {
                item.targetUri?.let { MediaStoreSink(resolver, item.name, Uri.parse(it)) {}.discard() }
            }
            val client = api() ?: return@launch
            runCatching {
                when {
                    item.outboxId != null -> client.rejectOutbox(item.outboxId)
                    item.direction == TransferDirection.UPLOAD -> {
                        val status = client.beginUpload(item.id, item.name, item.size, item.modifiedAt)
                        if (!status.completed) client.deleteUpload(status.id)
                    }
                }
            }
        }
    }

    fun clearFinished() {
        state.update { list -> list.filterNot { it.state == TransferState.DONE } }
        persist()
    }

    private fun add(added: List<TransferItem>) {
        if (added.isEmpty()) return
        state.update { it + added }
        persist()
        poke()
    }

    private suspend fun dispatchLoop() {
        while (true) {
            withTimeoutOrNull(RETRY_TICK_MILLIS) { wake.receive() }
            val client = api() ?: continue
            val now = System.currentTimeMillis()
            val runnable = state.value.filter {
                !running.containsKey(it.id) && when (it.state) {
                    TransferState.QUEUED -> true
                    TransferState.WAITING -> (retryAt[it.id] ?: 0) <= now
                    else -> false
                }
            }
            for (item in runnable.take(MAX_PARALLEL - running.size)) start(item, client)
        }
    }

    private fun start(item: TransferItem, client: ShareApi) {
        val position = AtomicLong(item.transferred)
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            run(item, client, position)
        }
        running[item.id] = Running(job, position, SpeedMeter(), System.nanoTime())
        change(item.id) { it.copy(state = TransferState.RUNNING, error = null) }
        job.invokeOnCompletion {
            running.remove(item.id)
            poke()
        }
        job.start()
    }

    private suspend fun run(item: TransferItem, client: ShareApi, position: AtomicLong) {
        try {
            when (item.direction) {
                TransferDirection.UPLOAD -> upload(item, client, position)
                TransferDirection.DOWNLOAD -> download(item, client, position)
            }
            attempts.remove(item.id)
            change(item.id) { it.copy(state = TransferState.DONE, transferred = it.size, speed = 0.0) }
            AppLog.add("Xong ${item.name}")
            persist()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            onApiFailure(item, client, e)
        } catch (e: SourceUnavailable) {
            fail(item, e.message ?: "Không đọc được file")
        } catch (e: IOException) {
            onIoFailure(item, e)
        } catch (e: SecurityException) {
            fail(item, "Không có quyền truy cập file")
        }
    }

    private suspend fun upload(item: TransferItem, client: ShareApi, position: AtomicLong) {
        val source = ContentUploadSource(
            resolver,
            Uri.parse(item.sourceUri ?: throw SourceUnavailable("Thiếu đường dẫn file")),
            item.name,
            item.size,
            item.modifiedAt,
        )
        Transfers(client).upload(item.id, source) { position.set(it) }
    }

    private suspend fun download(item: TransferItem, client: ShareApi, position: AtomicLong) {
        val sink = MediaStoreSink(resolver, item.name, item.targetUri?.let(Uri::parse)) { uri ->
            change(item.id) { it.copy(targetUri = uri.toString()) }
            persist()
        }
        var etag = item.etag
        Transfers(client).download(
            path = item.remotePath ?: throw IOException("Thiếu đường dẫn tải"),
            size = item.size,
            etag = etag,
            sink = sink,
            onEtag = { value ->
                if (value != etag) {
                    etag = value
                    change(item.id) { it.copy(etag = value) }
                    persist()
                }
            },
            onProgress = { position.set(it) },
        )
        item.outboxId?.let { id ->
            try {
                client.ackOutbox(id)
            } catch (e: ApiFailure) {
                // Already gone from the PC's queue. The file itself arrived intact.
                if (e.status != 404) throw e
            }
        }
    }

    private suspend fun onApiFailure(item: TransferItem, client: ShareApi, e: ApiFailure) {
        when {
            e.status == 401 -> scheduleRetry(item, "Đang chờ ghép đôi lại")
            e.code == "source_gone" -> {
                item.outboxId?.let { id -> runCatching { client.rejectOutbox(id) } }
                fail(item, e.message ?: "File nguồn đã bị xoá")
            }
            e.status == 507 -> fail(item, e.message ?: "PC đã hết dung lượng")
            else -> fail(item, e.message ?: "Lỗi từ PC")
        }
    }

    private fun onIoFailure(item: TransferItem, e: IOException) {
        if (e.message?.contains("ENOSPC") == true) {
            fail(item, "Máy đã hết dung lượng")
            return
        }
        AppLog.add("Mất kết nối khi chuyển ${item.name}: ${e.message}")
        onConnectionSuspect()
        scheduleRetry(item, "Đang chờ kết nối lại")
    }

    private fun scheduleRetry(item: TransferItem, message: String) {
        val attempt = attempts.merge(item.id, 1, Int::plus) ?: 1
        val backoff = min(MAX_BACKOFF_MILLIS, 1000L shl min(attempt - 1, 5))
        retryAt[item.id] = System.currentTimeMillis() + backoff
        change(item.id) { it.copy(state = TransferState.WAITING, error = message, speed = 0.0) }
        persist()
    }

    private fun fail(item: TransferItem, message: String) {
        AppLog.add("Lỗi ${item.name}: $message")
        change(item.id) { it.copy(state = TransferState.FAILED, error = message, speed = 0.0) }
        persist()
    }

    /** Publishes byte counters a few times a second, so the UI is not redrawn for every buffer. */
    private suspend fun progressLoop() {
        while (true) {
            delay(PROGRESS_MILLIS)
            if (running.isEmpty()) continue
            state.update { list ->
                list.map { item ->
                    val entry = running[item.id] ?: return@map item
                    val bytes = entry.position.get()
                    val seconds = (System.nanoTime() - entry.startedNanos) / 1e9
                    item.copy(transferred = bytes, speed = entry.meter.sample(bytes, seconds))
                }
            }
        }
    }

    private fun change(id: String, transform: (TransferItem) -> TransferItem) {
        state.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun TransferItem.isActive() =
        state == TransferState.QUEUED || state == TransferState.RUNNING || state == TransferState.WAITING

    private fun persist() {
        synchronized(saveLock) {
            val all = state.value
            val keptFinished = all.filter { it.state == TransferState.DONE }.takeLast(MAX_KEPT_FINISHED).toSet()
            val text = ApiJson.encodeToString(all.filter { it.state != TransferState.DONE || it in keptFinished })
            val temp = File(file.path + ".tmp")
            temp.writeText(text)
            temp.renameTo(file)
        }
    }

    private fun load(): List<TransferItem> {
        if (!file.exists()) return emptyList()
        return runCatching { ApiJson.decodeFromString<List<TransferItem>>(file.readText()) }
            .getOrDefault(emptyList())
            .map { if (it.state == TransferState.RUNNING) it.copy(state = TransferState.QUEUED) else it }
    }

    private companion object {
        const val MAX_PARALLEL = 3
        const val MAX_KEPT_FINISHED = 50
        const val PROGRESS_MILLIS = 300L
        const val RETRY_TICK_MILLIS = 1000L
        const val MAX_BACKOFF_MILLIS = 15_000L
    }
}

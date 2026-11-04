package com.sharefiles.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.sharefiles.app.core.ApiFailure
import com.sharefiles.app.core.ApiJson
import com.sharefiles.app.core.AppLog
import com.sharefiles.app.core.PairLink
import com.sharefiles.app.core.PairedServer
import com.sharefiles.app.core.ShareApi
import com.sharefiles.app.core.pinnedClient
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

sealed interface Connection {
    data object Idle : Connection
    data object Searching : Connection
    data class Online(val host: String) : Connection
}

/** App-wide state: who we are paired with, how to reach them, and the transfer queue. */
class AppState(private val context: Context) : DefaultLifecycleObserver {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val prefs = context.getSharedPreferences("share_files", Context.MODE_PRIVATE)
    private val discovery = ServiceDiscovery(context)
    private val foreground = MutableStateFlow(false)
    private val pairedState = MutableStateFlow(loadPaired())
    private val connectionState = MutableStateFlow<Connection>(Connection.Idle)
    private val pairingState = MutableStateFlow<PairingStatus>(PairingStatus.None)
    @Volatile private var currentApi: ShareApi? = null
    @Volatile private var suspect = false
    private var lastTried: String? = null

    val paired: StateFlow<PairedServer?> = pairedState
    val connection: StateFlow<Connection> = connectionState
    val pairing: StateFlow<PairingStatus> = pairingState

    val queue = TransferQueue(context, scope, { currentApi }) { suspect = true }

    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }
    }

    init {
        scope.launch { keepConnected() }
        scope.launch {
            queue.hasActive.collect { active -> if (active) TransferService.start(context) }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground.value = true
        // Transfers restored after a restart need the service too, and it can only start from here.
        if (queue.hasActive.value) TransferService.start(context)
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground.value = false
    }

    fun api(): ShareApi? = currentApi

    fun addUris(uris: List<Uri>) {
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { uri ->
                // Keeps access across restarts where the provider allows it, so a transfer can resume.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.contentResolver.describe(uri).also {
                    if (it == null) AppLog.add("Không đọc được thông tin của $uri")
                }
            }
            queue.addUploads(files)
        }
    }

    fun pair(text: String) {
        val link = PairLink.parse(text)
        if (link == null) {
            pairingState.value = PairingStatus.Failed("Đây không phải mã ghép đôi của Share Files")
            return
        }
        pairingState.value = PairingStatus.Working(link.serverName)
        scope.launch(Dispatchers.IO) {
            pairingState.value = try {
                runPairing(link)
                PairingStatus.None
            } catch (e: ApiFailure) {
                PairingStatus.Failed(e.message ?: "PC từ chối ghép đôi")
            } catch (e: IOException) {
                AppLog.add("Ghép đôi thất bại: ${e.message}")
                PairingStatus.Failed("Không kết nối được ${link.serverName}. Kiểm tra lại WiFi và app trên PC.")
            }
        }
    }

    fun dismissPairingError() {
        pairingState.value = PairingStatus.None
    }

    fun unpair() {
        prefs.edit { remove(KEY_PAIRED) }
        currentApi = null
        pairedState.value = null
        connectionState.value = Connection.Idle
    }

    private suspend fun runPairing(link: PairLink) {
        discovery.start()
        val client = pinnedClient(link.fingerprint)
        var lastFailure: IOException? = null
        val hosts = buildList {
            discovery.hosts.value[link.serverId]?.let(::add)
            addAll(link.addresses)
        }.distinct()

        for (host in hosts) {
            val api = ShareApi(client, baseUrl(host, link.port), null)
            try {
                if (api.info().id != link.serverId) continue
                val response = api.pair(link.secret, deviceId, deviceName())
                val server = PairedServer(
                    serverId = response.serverId,
                    serverName = response.serverName,
                    fingerprint = link.fingerprint,
                    token = response.token,
                    port = link.port,
                    addresses = link.addresses,
                    lastHost = host,
                )
                save(server)
                pairedState.value = server
                AppLog.add("Đã ghép đôi với ${server.serverName} tại $host")
                return
            } catch (e: IOException) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IOException("Chưa thấy PC trên mạng")
    }

    /** Runs while the user can see the app or a transfer is in flight. */
    private suspend fun keepConnected() {
        combine(pairedState, foreground, queue.hasActive) { server, visible, active ->
            server?.takeIf { visible || active }
        }.collectLatest { server ->
            if (server == null) {
                currentApi = null
                connectionState.value = Connection.Idle
                discovery.stop()
                return@collectLatest
            }
            discovery.start()
            connectionLoop(server)
        }
    }

    private suspend fun connectionLoop(server: PairedServer) {
        val client = pinnedClient(server.fingerprint)
        while (true) {
            var api = currentApi
            if (api == null || suspect) {
                suspect = false
                currentApi = null
                connectionState.value = Connection.Searching
                val host = findHost(server)
                if (host == null) {
                    // mDNS results can take a moment to arrive. Wake early if one does.
                    withTimeoutOrNull(SEARCH_DELAY_MILLIS) {
                        discovery.hosts.first { it.containsKey(server.serverId) && it[server.serverId] != lastTried }
                    }
                    continue
                }
                api = ShareApi(client, baseUrl(host, server.port), server.token)
                currentApi = api
                connectionState.value = Connection.Online(host)
                AppLog.add("Đã kết nối ${server.serverName} tại $host")
                queue.poke()
                if (server.lastHost != host) save((loadPaired() ?: server).copy(lastHost = host))
            }

            try {
                queue.addOutbox(api.outbox())
                delay(POLL_MILLIS)
            } catch (e: ApiFailure) {
                if (e.status == 401) {
                    AppLog.add("PC đã huỷ ghép đôi thiết bị này")
                    unpair()
                    return
                }
                delay(POLL_MILLIS)
            } catch (e: IOException) {
                AppLog.add("Mất kết nối: ${e.message}")
                suspect = true
            }
        }
    }

    private suspend fun findHost(server: PairedServer): String? {
        val client = pinnedClient(server.fingerprint)
        lastTried = discovery.hosts.value[server.serverId]
        val candidates = buildList {
            lastTried?.let(::add)
            server.lastHost?.let(::add)
            addAll(server.addresses)
        }.distinct()

        for (host in candidates) {
            try {
                if (ShareApi(client, baseUrl(host, server.port), null).info().id == server.serverId) return host
            } catch (_: IOException) {
                // Try the next address.
            } catch (_: ApiFailure) {
                // Something else answered on this address.
            }
        }
        return null
    }

    private fun baseUrl(host: String, port: Int) = "https://$host:$port"

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun save(server: PairedServer) {
        prefs.edit { putString(KEY_PAIRED, ApiJson.encodeToString(server)) }
    }

    private fun loadPaired(): PairedServer? =
        prefs.getString(KEY_PAIRED, null)?.let { text ->
            runCatching { ApiJson.decodeFromString<PairedServer>(text) }.getOrNull()
        }

    private companion object {
        const val KEY_PAIRED = "paired"
        const val KEY_DEVICE_ID = "device_id"
        const val POLL_MILLIS = 3000L
        const val SEARCH_DELAY_MILLIS = 2000L
    }
}

sealed interface PairingStatus {
    data object None : PairingStatus
    data class Working(val serverName: String) : PairingStatus
    data class Failed(val message: String) : PairingStatus
}

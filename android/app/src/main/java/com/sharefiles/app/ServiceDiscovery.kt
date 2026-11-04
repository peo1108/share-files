package com.sharefiles.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.sharefiles.app.core.AppLog
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Finds PCs on the network over mDNS. [hosts] maps a server id to its current IPv4 address. */
class ServiceDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val multicast = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("sharefiles-discovery")
        .apply { setReferenceCounted(false) }

    private val found = MutableStateFlow<Map<String, String>>(emptyMap())
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    val hosts: StateFlow<Map<String, String>> = found

    @Synchronized
    fun start() {
        if (listener != null) return
        multicast.acquire()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) = enqueue(service)
            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.add("Không tìm được PC qua mDNS (mã $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    @Synchronized
    fun stop() {
        val current = listener ?: return
        listener = null
        pending.clear()
        runCatching { nsd.stopServiceDiscovery(current) }
        multicast.release()
    }

    @Synchronized
    private fun enqueue(service: NsdServiceInfo) {
        pending.addLast(service)
        resolveNext()
    }

    /** NsdManager only allows one resolve at a time. */
    @Synchronized
    private fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true

        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(service: NsdServiceInfo) {
                val id = service.attributes["id"]?.toString(Charsets.UTF_8)
                @Suppress("DEPRECATION")
                val host = (service.host as? Inet4Address)?.hostAddress
                if (id != null && host != null) {
                    found.update { it + (id to host) }
                    AppLog.add("Thấy PC ${service.serviceName} tại $host")
                }
                finishResolve()
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) = finishResolve()
        })
    }

    @Synchronized
    private fun finishResolve() {
        resolving = false
        resolveNext()
    }

    private companion object {
        const val SERVICE_TYPE = "_sharefiles._tcp"
    }
}

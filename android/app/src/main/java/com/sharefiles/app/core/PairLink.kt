package com.sharefiles.app.core

import java.net.URLDecoder

/** The contents of the pairing QR code. See protocol/PROTOCOL.md. */
data class PairLink(
    val serverId: String,
    val serverName: String,
    val fingerprint: String,
    val secret: String,
    val port: Int,
    val addresses: List<String>,
) {
    companion object {
        private const val PREFIX = "sharefiles://pair?"
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): PairLink? {
            val value = text.trim()
            if (!value.startsWith(PREFIX)) return null

            val query = value.removePrefix(PREFIX).split('&').associate { part ->
                val key = part.substringBefore('=')
                val encoded = part.substringAfter('=', "")
                key to URLDecoder.decode(encoded, Charsets.UTF_8.name())
            }

            val id = query["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val fingerprint = query["fp"]?.takeIf { FINGERPRINT.matches(it) } ?: return null
            val secret = query["secret"]?.takeIf { it.isNotEmpty() } ?: return null
            val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

            return PairLink(
                serverId = id,
                serverName = query["name"]?.takeIf { it.isNotEmpty() } ?: "PC",
                fingerprint = fingerprint,
                secret = secret,
                port = port,
                addresses = query["addrs"].orEmpty().split(',').filter { it.isNotBlank() },
            )
        }
    }
}

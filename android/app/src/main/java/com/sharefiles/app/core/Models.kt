package com.sharefiles.app.core

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(val id: String, val name: String, val apiVersion: Int)

@Serializable
data class PairResponse(val token: String, val serverId: String, val serverName: String)

@Serializable
data class RootDto(val id: String, val name: String)

@Serializable
data class BrowseEntry(val name: String, val isDirectory: Boolean, val size: Long, val modifiedAt: String)

@Serializable
data class BrowseResponse(val root: String, val path: String, val entries: List<BrowseEntry>)

@Serializable
data class UploadStatus(val id: String, val offset: Long, val completed: Boolean, val savedName: String? = null)

@Serializable
data class OutboxEntry(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedAt: String,
    val sourceMissing: Boolean,
)

@Serializable
internal data class ApiErrorBody(val code: String = "", val message: String = "", val offset: Long? = null)

/** A paired PC. Everything needed to find it again and prove who it is. */
@Serializable
data class PairedServer(
    val serverId: String,
    val serverName: String,
    val fingerprint: String,
    val token: String,
    val port: Int,
    val addresses: List<String>,
    val lastHost: String? = null,
)

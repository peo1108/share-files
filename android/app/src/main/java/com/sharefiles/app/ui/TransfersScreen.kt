package com.sharefiles.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sharefiles.app.AppState
import com.sharefiles.app.Connection
import com.sharefiles.app.TransferDirection
import com.sharefiles.app.TransferItem
import com.sharefiles.app.TransferState

@Composable
fun TransfersScreen(state: AppState, wide: Boolean) {
    val items by state.queue.items.collectAsStateWithLifecycle()
    val connection by state.connection.collectAsStateWithLifecycle()
    val server by state.paired.collectAsStateWithLifecycle()

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) state.addUris(uris)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) state.addUris(uris)
    }
    val sendMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val sendFiles = { pickFiles.launch(arrayOf("*/*")) }

    val status: @Composable (Modifier) -> Unit = { modifier ->
        StatusCard(server?.serverName ?: "PC", connection, items.sumOf { it.speed }, modifier)
    }
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton("Gửi ảnh", Modifier.weight(1f), Icons.Rounded.Image, onClick = sendMedia)
            GhostButtonLarge("Gửi file", Modifier.weight(1f), sendFiles)
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                status(Modifier.fillMaxWidth())
                actions()
            }
            TransferList(state, items, Modifier.weight(0.58f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status(Modifier.fillMaxWidth())
            actions()
            TransferList(state, items, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun GhostButtonLarge(text: String, modifier: Modifier, onClick: () -> Unit) {
    GhostButton(text, modifier.height(50.dp), Icons.Rounded.Description, onClick = onClick)
}

@Composable
private fun StatusCard(serverName: String, connection: Connection, speed: Double, modifier: Modifier) {
    Column(modifier.glass().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (color, label) = when (connection) {
                is Connection.Online -> Palette.Success to "Đã kết nối"
                Connection.Searching -> Palette.Amber to "Đang tìm PC"
                Connection.Idle -> Palette.Faint to "Chưa kết nối"
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(9.dp))
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Text(serverName, color = Palette.Text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        (connection as? Connection.Online)?.let {
            Text(it.host, color = Palette.Faint, fontSize = 12.sp)
        }
        if (speed > 0) {
            Spacer(Modifier.height(10.dp))
            Text(formatSpeed(speed), color = Palette.Amber, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TransferList(state: AppState, items: List<TransferItem>, modifier: Modifier) {
    val ordered = items.asReversed().sortedBy { it.state == TransferState.DONE }
    Column(modifier.glass()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Danh sách chuyển", Modifier.weight(1f))
            val canClear = items.any { it.state == TransferState.DONE }
            TextButton(onClick = state.queue::clearFinished, enabled = canClear) {
                Text("Xoá mục đã xong", color = if (canClear) Palette.Subtle else Color.Transparent, fontSize = 12.sp)
            }
        }
        if (ordered.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.SwapVert, null, tint = Palette.Faint, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Chưa có gì đang chuyển", color = Palette.Subtle, fontSize = 14.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, bottom = 10.dp)) {
                items(ordered, key = { it.id }) { item ->
                    TransferRow(item, onCancel = { state.queue.cancel(item.id) }, onRetry = { state.queue.retry(item.id) })
                }
            }
        }
    }
}

@Composable
private fun TransferRow(item: TransferItem, onCancel: () -> Unit, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (item.direction == TransferDirection.UPLOAD) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                null,
                tint = when (item.state) {
                    TransferState.DONE -> Palette.Success
                    TransferState.FAILED -> Palette.Danger
                    else -> Palette.Text
                },
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    Modifier.weight(1f),
                    color = Palette.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.speed > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatSpeed(item.speed), color = Palette.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (item.state != TransferState.DONE) {
                Spacer(Modifier.height(6.dp))
                GradientProgress(if (item.size > 0) item.transferred.toFloat() / item.size else 0f)
            }
            Spacer(Modifier.height(5.dp))
            Text(detail(item), color = if (item.state == TransferState.FAILED) Palette.Danger else Palette.Faint, fontSize = 12.sp, maxLines = 2)
        }
        if (item.state == TransferState.FAILED) {
            IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Thử lại", tint = Palette.Subtle) }
        }
        if (item.state != TransferState.DONE) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Huỷ", tint = Palette.Subtle) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

private fun detail(item: TransferItem): String = when (item.state) {
    TransferState.QUEUED -> "${formatBytes(item.size)} · đang xếp hàng"
    TransferState.RUNNING -> "${formatBytes(item.transferred)} / ${formatBytes(item.size)}"
    TransferState.WAITING -> "${formatBytes(item.transferred)} / ${formatBytes(item.size)} · ${item.error ?: "đang chờ"}"
    TransferState.DONE -> "${formatBytes(item.size)} · xong"
    TransferState.FAILED -> item.error ?: "Lỗi"
}

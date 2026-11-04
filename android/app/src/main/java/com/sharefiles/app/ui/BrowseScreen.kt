package com.sharefiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sharefiles.app.AppState
import com.sharefiles.app.Connection
import com.sharefiles.app.core.ApiFailure
import com.sharefiles.app.core.BrowseEntry
import com.sharefiles.app.core.RootDto
import java.io.IOException

@Composable
fun BrowseScreen(state: AppState) {
    val connection by state.connection.collectAsStateWithLifecycle()
    val online = connection is Connection.Online

    var roots by remember { mutableStateOf<List<RootDto>>(emptyList()) }
    var rootId by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        val api = state.api() ?: return@LaunchedEffect
        try {
            roots = api.roots()
            if (rootId == null || roots.none { it.id == rootId }) rootId = roots.firstOrNull()?.id
        } catch (e: IOException) {
            error = "Không tải được danh sách thư mục"
        } catch (e: ApiFailure) {
            error = e.message
        }
    }

    LaunchedEffect(rootId, path, online) {
        val root = rootId ?: return@LaunchedEffect
        val api = state.api() ?: return@LaunchedEffect
        loading = true
        error = null
        selected.clear()
        try {
            entries = api.browse(root, path).entries
        } catch (e: IOException) {
            error = "Mất kết nối với PC"
        } catch (e: ApiFailure) {
            if (e.status == 404 && path.isNotEmpty()) path = ""
            error = e.message
        }
        loading = false
    }

    BackHandler(enabled = path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            roots.forEach { root ->
                val active = root.id == rootId
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) Palette.Accent else androidx.compose.ui.graphics.SolidColor(Palette.Card))
                        .clickable {
                            rootId = root.id
                            path = ""
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(root.name, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().glass()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 14.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { path = path.substringBeforeLast('/', "") }, enabled = path.isNotEmpty()) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        "Quay lại",
                        tint = if (path.isNotEmpty()) Palette.Text else Palette.Faint,
                    )
                }
                Text(
                    path.ifEmpty { roots.firstOrNull { it.id == rootId }?.name ?: "Thư mục trên PC" },
                    color = Palette.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }

            when {
                !online -> Message("Đang chờ kết nối với PC")
                loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Pink)
                }
                error != null -> Message(error ?: "")
                entries.isEmpty() -> Message("Thư mục trống")
                else -> LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    items(entries, key = { it.name }) { entry ->
                        EntryRow(entry, entry.name in selected) {
                            if (entry.isDirectory) {
                                path = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                            } else if (!selected.remove(entry.name)) {
                                selected.add(entry.name)
                            }
                        }
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            val chosen = entries.filter { it.name in selected }
            GradientButton(
                "Tải ${chosen.size} file về máy · ${formatBytes(chosen.sumOf { it.size })}",
                Modifier.fillMaxWidth(),
                Icons.Rounded.Download,
            ) {
                val root = rootId ?: return@GradientButton
                chosen.forEach { entry ->
                    val remote = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                    state.queue.addDownload(root, remote, entry.name, entry.size)
                }
                selected.clear()
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, color = Palette.Subtle, fontSize = 14.sp)
    }
}

@Composable
private fun EntryRow(entry: BrowseEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            null,
            tint = if (entry.isDirectory) Palette.Amber else Palette.Cyan,
            modifier = Modifier.size(25.dp),
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Palette.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) {
                Spacer(Modifier.height(2.dp))
                Text(formatBytes(entry.size), color = Palette.Faint, fontSize = 12.sp)
            }
        }
        if (!entry.isDirectory) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = if (selected) Palette.Pink else Palette.Faint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

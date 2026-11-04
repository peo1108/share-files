package com.sharefiles.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sharefiles.app.AppState
import com.sharefiles.app.PairingStatus

@Composable
fun PairScreen(state: AppState) {
    val context = LocalContext.current
    val status by state.pairing.collectAsStateWithLifecycle()
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var showPaste by remember { mutableStateOf(false) }
    var lastCode by remember { mutableStateOf<String?>(null) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    LaunchedEffect(Unit) {
        if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Share Files", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Text(
                "Mở Share Files trên PC rồi quét mã QR để ghép đôi",
                color = Palette.Subtle,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .glass(28),
                contentAlignment = Alignment.Center,
            ) {
                if (hasCamera) {
                    QrScanner(
                        onCode = { code ->
                            if (code != lastCode) {
                                lastCode = code
                                state.pair(code)
                            }
                        },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.QrCodeScanner, null, tint = Palette.Faint, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        GhostButton("Cho phép dùng camera") { askCamera.launch(Manifest.permission.CAMERA) }
                    }
                }
                when (val current = status) {
                    is PairingStatus.Working -> Column(
                        Modifier.fillMaxSize().background(Palette.Night.copy(alpha = 0.75f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = Palette.Pink)
                        Spacer(Modifier.height(14.dp))
                        Text("Đang ghép đôi với ${current.serverName}", color = Palette.Text)
                    }
                    else -> Unit
                }
            }

            (status as? PairingStatus.Failed)?.let { failed ->
                Spacer(Modifier.height(14.dp))
                Text(failed.message, color = Palette.Danger, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    lastCode = null
                    state.dismissPairingError()
                }) { Text("Thử lại", color = Palette.Text) }
            }

            Spacer(Modifier.height(18.dp))
            GhostButton("Dán link ghép đôi", icon = Icons.Rounded.ContentPaste) { showPaste = true }
        }
    }

    if (showPaste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Dán link ghép đôi") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("sharefiles://pair?...") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPaste = false
                    state.pair(text)
                }) { Text("Ghép đôi") }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }) { Text("Huỷ") }
            },
        )
    }
}

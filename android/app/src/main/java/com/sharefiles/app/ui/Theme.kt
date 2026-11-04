package com.sharefiles.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Palette {
    val Night = Color(0xFF0B0A1F)
    val Dusk = Color(0xFF1A1040)
    val Violet = Color(0xFF7C4DFF)
    val Pink = Color(0xFFFF4D9D)
    val Amber = Color(0xFFFFB547)
    val Cyan = Color(0xFF22D3EE)
    val Success = Color(0xFF34D399)
    val Danger = Color(0xFFFB7185)
    val Text = Color.White
    val Subtle = Color.White.copy(alpha = 0.7f)
    val Faint = Color.White.copy(alpha = 0.5f)
    val Card = Color.White.copy(alpha = 0.08f)
    val CardBorder = Color.White.copy(alpha = 0.14f)
    val Accent = Brush.linearGradient(listOf(Violet, Pink, Amber))
    val AccentHorizontal = Brush.horizontalGradient(listOf(Violet, Pink, Amber))
}

@Composable
fun ShareFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Violet,
            secondary = Palette.Pink,
            tertiary = Palette.Amber,
            background = Palette.Night,
            surface = Palette.Dusk,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
            error = Palette.Danger,
        ),
        content = content,
    )
}

/** The slowly drifting aurora behind every screen. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Palette.Night, Palette.Dusk)))
            .drawBehind {
                val w = size.width
                val h = size.height
                val unit = maxOf(w, h)
                fun blob(color: Color, alpha: Float, x: Float, y: Float, radius: Float) {
                    val center = Offset(x, y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
                blob(Palette.Violet, 0.60f, w * (0.05f + 0.25f * drift), h * (0.02f + 0.10f * drift), unit * 0.55f)
                blob(Palette.Pink, 0.45f, w * (1.0f - 0.30f * drift), h * (0.98f - 0.14f * drift), unit * 0.60f)
                blob(Palette.Cyan, 0.30f, w * (0.95f - 0.20f * drift), h * (0.05f + 0.12f * drift), unit * 0.42f)
            },
        content = content,
    )
}

fun Modifier.glass(radius: Int = 22): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .clip(shape)
        .background(Palette.Card)
        .border(1.dp, Palette.CardBorder, shape)
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(Palette.Accent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Palette.Text,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(Palette.Card)
            .border(1.dp, Palette.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
fun GradientProgress(fraction: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.Card),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.AccentHorizontal),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

private val Symbols = DecimalFormatSymbols(Locale.forLanguageTag("vi-VN"))
private val OneDecimal = DecimalFormat("0.0", Symbols)
private val NoDecimal = DecimalFormat("0", Symbols)

fun formatBytes(bytes: Double): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val number = synchronized(Symbols) {
        if (unit == 0 || value >= 100) NoDecimal.format(value) else OneDecimal.format(value)
    }
    return "$number ${units[unit]}"
}

fun formatBytes(bytes: Long) = formatBytes(bytes.toDouble())

fun formatSpeed(bytesPerSecond: Double) = formatBytes(bytesPerSecond) + "/s"

/** The same speed in megabits, the unit routers and ISPs quote. */
fun formatBitrate(bytesPerSecond: Double): String {
    val mbps = bytesPerSecond * 8 / 1_000_000
    val number = synchronized(Symbols) {
        if (mbps >= 100) NoDecimal.format(mbps) else OneDecimal.format(mbps)
    }
    return "$number Mbps"
}

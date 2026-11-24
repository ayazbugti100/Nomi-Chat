package com.example.ui.screens

import android.util.Base64
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.audio.VoicePlaybackUiState
import com.example.audio.VoiceRecordingUiState
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThemeStyle
import com.example.data.model.ChatThread
import com.example.ui.components.AvatarCircle
import com.example.ui.theme.DarkBubbleMe
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DangerRed
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.EmeraldVibrant
import com.example.ui.theme.MintBubbleMe
import com.example.ui.theme.MintLight
import kotlinx.coroutines.delay
import kotlin.math.abs

data class ChatThemePalette(
    val backgroundBase: Color,
    val wallpaperOverlay: Color,
    val wallpaperAlpha: Float,
    val bubbleMe: Color,
    val bubbleThem: Color,
    val textMe: Color,
    val textThem: Color,
    val mutedTextMe: Color,
    val mutedTextThem: Color,
    val accent: Color
)

@Composable
fun rememberChatThemePalette(style: ChatThemeStyle, isDark: Boolean): ChatThemePalette {
    val defaultText = MaterialTheme.colorScheme.onSurface
    val defaultMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val defaultSurface = MaterialTheme.colorScheme.surface
    return remember(style, isDark, defaultText, defaultMuted, defaultSurface) {
        when (style) {
            ChatThemeStyle.CLASSIC -> if (isDark) {
                ChatThemePalette(
                    backgroundBase = Color(0xFF0B141A),
                    wallpaperOverlay = Color(0xFF0B141A),
                    wallpaperAlpha = 0.12f,
                    bubbleMe = Color(0xFF005C4B),
                    bubbleThem = Color(0xFF202C33),
                    textMe = Color(0xFFE9EDEF),
                    textThem = Color(0xFFE9EDEF),
                    mutedTextMe = Color(0xFFAEC7C1),
                    mutedTextThem = Color(0xFF8696A0),
                    accent = EmeraldVibrant
                )
            } else {
                ChatThemePalette(
                    backgroundBase = Color(0xFFEFEAE2),
                    wallpaperOverlay = Color(0xFFEFEAE2),
                    wallpaperAlpha = 0.85f,
                    bubbleMe = Color(0xFFD9FDD3),
                    bubbleThem = Color.White,
                    textMe = Color(0xFF111B21),
                    textThem = Color(0xFF111B21),
                    mutedTextMe = Color(0xFF54656F),
                    mutedTextThem = Color(0xFF667781),
                    accent = EmeraldMid
                )
            }
            ChatThemeStyle.EMERALD -> ChatThemePalette(
                backgroundBase = if (isDark) Color(0xFF091D16) else Color(0xFFE4F4EC),
                wallpaperOverlay = if (isDark) EmeraldDeep else MintLight,
                wallpaperAlpha = if (isDark) 0.22f else 0.78f,
                bubbleMe = if (isDark) DarkBubbleMe else MintBubbleMe,
                bubbleThem = if (isDark) DarkSurface else Color.White,
                textMe = defaultText,
                textThem = defaultText,
                mutedTextMe = defaultMuted,
                mutedTextThem = defaultMuted,
                accent = EmeraldMid
            )
            ChatThemeStyle.OCEAN -> ChatThemePalette(
                backgroundBase = if (isDark) Color(0xFF0B1A26) else Color(0xFFE2F0F8),
                wallpaperOverlay = if (isDark) Color(0xFF0F2B40) else Color(0xFFD6ECFA),
                wallpaperAlpha = if (isDark) 0.24f else 0.78f,
                bubbleMe = if (isDark) Color(0xFF0F4C75) else Color(0xFFCDEBFD),
                bubbleThem = if (isDark) Color(0xFF162735) else Color.White,
                textMe = if (isDark) Color.White else Color(0xFF0E2233),
                textThem = defaultText,
                mutedTextMe = if (isDark) Color(0xFFB3D5EA) else Color(0xFF476378),
                mutedTextThem = defaultMuted,
                accent = Color(0xFF0284C7)
            )
            ChatThemeStyle.MIDNIGHT -> ChatThemePalette(
                backgroundBase = Color(0xFF0C1117),
                wallpaperOverlay = Color(0xFF141B24),
                wallpaperAlpha = 0.14f,
                bubbleMe = Color(0xFF14533C),
                bubbleThem = Color(0xFF1E2630),
                textMe = Color(0xFFF3F4F6),
                textThem = Color(0xFFE5E7EB),
                mutedTextMe = Color(0xFFB5CEC4),
                mutedTextThem = Color(0xFF9CA3AF),
                accent = EmeraldBright
            )
            ChatThemeStyle.SAND -> ChatThemePalette(
                backgroundBase = if (isDark) Color(0xFF1E1913) else Color(0xFFF5ECE0),
                wallpaperOverlay = if (isDark) Color(0xFF2B2318) else Color(0xFFF3E5D0),
                wallpaperAlpha = if (isDark) 0.22f else 0.80f,
                bubbleMe = if (isDark) Color(0xFF5A4324) else Color(0xFFFFE5B8),
                bubbleThem = if (isDark) Color(0xFF29231B) else Color.White,
                textMe = if (isDark) Color(0xFFFAF3EA) else Color(0xFF2A2116),
                textThem = defaultText,
                mutedTextMe = if (isDark) Color(0xFFD8C7B0) else Color(0xFF6B5B45),
                mutedTextThem = defaultMuted,
                accent = Color(0xFFB4691E)
            )
            ChatThemeStyle.LAVENDER -> ChatThemePalette(
                backgroundBase = if (isDark) Color(0xFF191424) else Color(0xFFF0ECFA),
                wallpaperOverlay = if (isDark) Color(0xFF261D38) else Color(0xFFEAE3FB),
                wallpaperAlpha = if (isDark) 0.22f else 0.80f,
                bubbleMe = if (isDark) Color(0xFF493275) else Color(0xFFE1D6FD),
                bubbleThem = if (isDark) Color(0xFF231D30) else Color.White,
                textMe = if (isDark) Color.White else Color(0xFF1F1635),
                textThem = defaultText,
                mutedTextMe = if (isDark) Color(0xFFD2C4F2) else Color(0xFF5A4D78),
                mutedTextThem = defaultMuted,
                accent = Color(0xFF7C3AED)
            )
        }
    }
}

fun formatDurationTimer(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val mins = safe / 60L
    val secs = safe % 60L
    return "$mins:${secs.toString().padStart(2, '0')}"
}

@Composable
fun MessageBubble(
    msg: ChatMessage,
    palette: ChatThemePalette,
    playbackState: VoicePlaybackUiState,
    searchQuery: String = "",
    isCurrentSearchMatch: Boolean = false,
    onToggleVoicePlayback: (ChatMessage) -> Unit = {},
    onPreviewImage: (String) -> Unit = {}
) {
    val isMe = msg.sender == "me"
    val bubbleColor = if (isMe) palette.bubbleMe else palette.bubbleThem
    val primaryTextColor = if (isMe) palette.textMe else palette.textThem
    val secondaryTextColor = if (isMe) palette.mutedTextMe else palette.mutedTextThem
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)
    }

    val decodedImageBytes = remember(msg.messageType, msg.mediaData) {
        if (msg.messageType == "image" && !msg.mediaData.isNullOrBlank() && msg.mediaData.startsWith("data:image")) {
            val b64 = msg.mediaData.substringAfter("base64,", "")
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        } else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_bubble_${msg.id}"),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 96.dp, max = 304.dp),
            shape = bubbleShape,
            color = bubbleColor,
            border = if (isCurrentSearchMatch) BorderStroke(2.dp, Color(0xFFFFB300)) else null,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                when (msg.messageType) {
                    "voice" -> {
                        val isThisPlaying = playbackState.playingMessageId == msg.id
                        val activePlaying = isThisPlaying && playbackState.isPlaying
                        val progress = if (isThisPlaying) playbackState.progress else 0f
                        val displaySeconds = if (isThisPlaying && playbackState.durationSec > 0L) {
                            if (activePlaying || playbackState.positionSec > 0L) playbackState.positionSec
                            else playbackState.durationSec
                        } else {
                            msg.durationSec.coerceAtLeast(1L)
                        }
                        val waveformSeed = remember(msg.id) {
                            val h = abs(msg.id.hashCode())
                            List(22) { idx ->
                                val v = ((h shr (idx % 12)) + idx * 7) % 10
                                (0.28f + (v / 10f) * 0.72f).coerceIn(0.25f, 1f)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .testTag("voice_bubble_${msg.id}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent)
                                    .clickable { onToggleVoicePlayback(msg) }
                                    .testTag("voice_play_button_${msg.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (activePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (activePlaying) "Pause voice message" else "Play voice message",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    waveformSeed.forEachIndexed { index, barRatio ->
                                        val threshold = index.toFloat() / waveformSeed.size.toFloat()
                                        val isPlayed = isThisPlaying && progress > threshold
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height((22f * barRatio).dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    if (isPlayed) palette.accent
                                                    else secondaryTextColor.copy(alpha = 0.36f)
                                                )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = formatDurationTimer(displaySeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = secondaryTextColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    "image" -> {
                        if (decodedImageBytes != null) {
                            AsyncImage(
                                model = decodedImageBytes,
                                contentDescription = "Photo message",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 130.dp, max = 240.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { msg.mediaData?.let(onPreviewImage) }
                            )
                            if (msg.text.isNotBlank() && msg.text != "📷 Photo") {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = buildHighlightedText(msg.text, searchQuery),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = primaryTextColor
                                )
                            }
                        } else {
                            Text(
                                text = buildHighlightedText(msg.text, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = primaryTextColor
                            )
                        }
                    }

                    "file" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(secondaryTextColor.copy(alpha = 0.1f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = "Attachment",
                                tint = palette.accent,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = buildHighlightedText(msg.text.removePrefix("📎 "), searchQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = primaryTextColor,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    "call" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Voice call",
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = buildHighlightedText(msg.text.removePrefix("📞 "), searchQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = primaryTextColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = buildHighlightedText(msg.text, searchQuery),
                            style = MaterialTheme.typography.bodyLarge,
                            color = primaryTextColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (msg.expiresAtMs > 0L) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = "Disappearing message",
                            tint = secondaryTextColor,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    if (msg.aiGenerated) {
                        Text(
                            text = "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = msg.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor
                    )
                    if (isMe) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Delivered",
                            tint = Color(0xFF1E9BF0),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun buildHighlightedText(text: String, query: String): AnnotatedString {
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val lowerText = text.lowercase()
        val lowerQuery = cleanQuery.lowercase()
        var startIndex = 0
        while (startIndex < lowerText.length) {
            val found = lowerText.indexOf(lowerQuery, startIndex)
            if (found < 0) break
            addStyle(
                style = SpanStyle(
                    background = Color(0xFFFFEB3B),
                    color = Color(0xFF111B21),
                    fontWeight = FontWeight.Bold
                ),
                start = found,
                end = found + lowerQuery.length
            )
            startIndex = found + lowerQuery.length
        }
    }
}

@Composable
fun InChatSearchBar(
    query: String,
    matchCount: Int,
    currentMatchIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("in_chat_search_bar"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search in conversation…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(EmeraldMid),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("in_chat_search_input")
                )
            }

            if (query.isNotBlank()) {
                Text(
                    text = if (matchCount == 0) "0/0" else "${currentMatchIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            IconButton(
                onClick = onPrevMatch,
                enabled = matchCount > 0,
                modifier = Modifier.testTag("in_chat_search_prev")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Previous match"
                )
            }
            IconButton(
                onClick = onNextMatch,
                enabled = matchCount > 0,
                modifier = Modifier.testTag("in_chat_search_next")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Next match"
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("in_chat_search_close")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close search"
                )
            }
        }
    }
}

@Composable
fun EmojiQuickPickerBar(
    onEmojiSelected: (String) -> Unit,
    onClose: () -> Unit
) {
    val emojis = remember {
        listOf(
            "😀", "😂", "🥰", "😍", "😊", "😎", "🥳", "😭", "🙏", "❤️",
            "💚", "🔥", "👍", "👏", "🙌", "🤝", "🎉", "💯", "✨", "🤩",
            "🤔", "😅", "🤗", "😇", "😉", "🌹", "☕", "🎶", "📸", "🎤"
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("emoji_picker_bar"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Emojis",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close emoji picker",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                emojis.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceRecordingBar(
    recordingState: VoiceRecordingUiState,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "recPulse")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_recording_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(DangerRed.copy(alpha = 0.12f))
                .testTag("cancel_voice_recording")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Cancel voice recording",
                tint = DangerRed
            )
        }

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(DangerRed)
                )
                Text(
                    text = formatDurationTimer(recordingState.elapsedSec),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val bars = if (recordingState.amplitudes.isEmpty()) {
                        List(16) { 0.2f }
                    } else {
                        recordingState.amplitudes.takeLast(18)
                    }
                    bars.forEach { amp ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((18f * amp.coerceIn(0.2f, 1f)).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(EmeraldMid)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(EmeraldVibrant)
                .clickable(onClick = onSend)
                .testTag("send_voice_recording"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send voice message",
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
fun AttachmentPickerDialog(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Attach Media or File",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onDismiss()
                            onPickPhoto()
                        }
                        .testTag("attach_photo_option"),
                    shape = RoundedCornerShape(14.dp),
                    color = MintLight
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Photo Gallery",
                            tint = EmeraldDark
                        )
                        Column {
                            Text(
                                text = "Photo from Gallery",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = EmeraldDeep
                            )
                            Text(
                                text = "Send an image in real time",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldDark.copy(alpha = 0.78f)
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onDismiss()
                            onPickFile()
                        }
                        .testTag("attach_document_option"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Document",
                            tint = EmeraldMid
                        )
                        Column {
                            Text(
                                text = "Document or File",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Attach a document name to chat",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DisappearingMessagesDialog(
    currentSeconds: Long,
    onSelectDuration: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0L to "Off",
        86_400L to "24 hours",
        604_800L to "7 days",
        7_776_000L to "90 days"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Disappearing messages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "New messages in this chat will expire after the selected duration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                options.forEach { (seconds, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSelectDuration(seconds)
                                onDismiss()
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSeconds == seconds,
                            onClick = {
                                onSelectDuration(seconds)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = EmeraldMid)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ChatThemePickerDialog(
    currentThemeKey: String,
    onSelectTheme: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val swatches = remember {
        mapOf(
            ChatThemeStyle.CLASSIC to Color(0xFFD9FDD3),
            ChatThemeStyle.EMERALD to EmeraldMid,
            ChatThemeStyle.OCEAN to Color(0xFF0284C7),
            ChatThemeStyle.MIDNIGHT to Color(0xFF1E293B),
            ChatThemeStyle.SAND to Color(0xFFD97706),
            ChatThemeStyle.LAVENDER to Color(0xFF7C3AED)
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Chat theme",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatThemeStyle.entries.forEach { style ->
                    val selected = ChatThemeStyle.fromKey(currentThemeKey) == style
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MintLight.copy(alpha = 0.65f) else Color.Transparent)
                            .clickable {
                                onSelectTheme(style.key)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(swatches[style] ?: EmeraldMid)
                                    .border(1.dp, Color.Black.copy(alpha = 0.14f), CircleShape)
                            )
                            Text(
                                text = style.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = EmeraldDark
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun MoreChatOptionsDialog(
    contact: ChatThread,
    smartAiEnabled: Boolean,
    onToggleSmartAi: (Boolean) -> Unit,
    onRefreshSmartReplies: () -> Unit,
    onToggleLockChat: () -> Unit,
    onClearMessages: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "More options",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = EmeraldMid
                        )
                        Text("Smart AI replies", style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(
                        checked = smartAiEnabled,
                        onCheckedChange = onToggleSmartAi,
                        colors = SwitchDefaults.colors(checkedTrackColor = EmeraldMid)
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onRefreshSmartReplies()
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = EmeraldDark)
                    Text("Generate smart replies now", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onToggleLockChat()
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (contact.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        tint = EmeraldDark
                    )
                    Text(
                        text = if (contact.locked) "Unlock chat" else "Lock chat",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onClearMessages()
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = DangerRed)
                    Text(
                        text = "Clear chat messages",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DangerRed
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun ConfirmDangerActionDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun InAppVoiceCallDialog(
    contact: ChatThread,
    onEndCall: () -> Unit
) {
    var elapsedSec by remember { mutableLongStateOf(0L) }
    var micMuted by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            elapsedSec += 1L
        }
    }

    Dialog(onDismissRequest = onEndCall) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("in_app_voice_call_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0B1F17),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Nomi Encrypted Voice Call",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldBright
                )
                AvatarCircle(
                    name = contact.name,
                    photoData = contact.photoData,
                    size = 92.dp
                )
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (elapsedSec < 2L) "Calling…" else formatDurationTimer(elapsedSec - 1L),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { micMuted = !micMuted },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (micMuted) Color.White else Color.White.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute microphone",
                            tint = if (micMuted) EmeraldDeep else Color.White
                        )
                    }

                    IconButton(
                        onClick = onEndCall,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(DangerRed)
                            .testTag("end_voice_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End call",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = { speakerOn = !speakerOn },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (speakerOn) Color.White else Color.White.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Speaker",
                            tint = if (speakerOn) EmeraldDeep else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageMessagePreviewDialog(
    mediaData: String,
    onDismiss: () -> Unit
) {
    val bytes = remember(mediaData) {
        val b64 = mediaData.substringAfter("base64,", "")
        runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (bytes != null) {
                    AsyncImage(
                        model = bytes,
                        contentDescription = "Full image preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 520.dp)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

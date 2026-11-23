package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BAN_REPORT_LIMIT
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.UiState
import com.example.ui.components.AvatarCircle
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.theme.DarkBubbleMe
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.ErrorContainerLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MintBubbleMe
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow

@Composable
fun ConversationScreen(
    contact: ChatThread,
    messagesState: UiState<List<ChatMessage>>,
    isContactTyping: Boolean,
    smartAiEnabled: Boolean,
    quickSuggestions: List<String>,
    isGeneratingSuggestions: Boolean,
    onToggleSmartAi: (Boolean) -> Unit,
    onRequestSuggestions: () -> Unit,
    onDismissSuggestions: () -> Unit,
    onSendMessage: (String) -> Unit,
    onReportContact: () -> Unit,
    onToggleBlockContact: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var messageText by remember { mutableStateOf("") }
    var showReportDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val isContactBanned = contact.accountStatus == "banned"
    val isContactBlocked = contact.blocked
    val isMessagingDisabled = isContactBanned || isContactBlocked
    val otherPartyTyping = (contact.typing || isContactTyping) && !isMessagingDisabled
    val messages = (messagesState as? UiState.Success)?.data.orEmpty()

    LaunchedEffect(messages.size, otherPartyTyping) {
        if (messages.isNotEmpty()) {
            // Index 0 is the E2E banner; messages start at index 1
            val targetIndex = if (otherPartyTyping) messages.size + 1 else messages.size
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        EmeraldWaveHeader(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("conv_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to chats",
                        tint = Color.White
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showProfileDialog = true }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .testTag("contact_profile_header"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarCircle(
                        name = contact.name,
                        isGroup = contact.threadType == "group",
                        showOnlineDot = contact.online && !isContactBanned && !isContactBlocked,
                        size = 40.dp
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isContactBanned) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ErrorRed
                                ) {
                                    Text(
                                        text = stringResource(R.string.banned_account_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        if (otherPartyTyping) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.testTag("header_typing_indicator")
                            ) {
                                TypingPulseDots(dotColor = MintOnlineGlow, dotSize = 5.dp)
                                Text(
                                    text = stringResource(R.string.status_typing),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MintOnlineGlow,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.testTag("e2e_header_badge")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.e2e_badge),
                                    tint = MintOnlineGlow,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = when {
                                        isContactBanned -> "Account banned"
                                        isContactBlocked -> "Blocked user"
                                        contact.online -> "Online • E2E Encrypted"
                                        else -> "Offline • E2E Encrypted"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MintOnlineGlow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Report User Action Button
                IconButton(
                    onClick = { showReportDialog = true },
                    modifier = Modifier.testTag("report_contact_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Flag,
                        contentDescription = stringResource(R.string.report_user),
                        tint = Color.White
                    )
                }

                // Smart AI Reply Toggle Pill
                Surface(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onToggleSmartAi(!smartAiEnabled) }
                        .testTag("smart_ai_toggle"),
                    shape = RoundedCornerShape(18.dp),
                    color = if (smartAiEnabled) EmeraldBright else Color.White.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Toggle Smart AI replies",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (smartAiEnabled) "AI On" else "AI Off",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (messagesState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = EmeraldMid)
                    }
                }
                is UiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = messagesState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                is UiState.Success -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "e2e_banner") {
                            EndToEndEncryptionBanner(contactName = contact.name)
                        }

                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(msg = msg)
                        }

                        if (otherPartyTyping) {
                            item(key = "typing_bubble") {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomEnd = 16.dp,
                                        bottomStart = 4.dp
                                    ),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.testTag("in_chat_typing_bubble")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TypingPulseDots(dotColor = EmeraldMid, dotSize = 6.dp)
                                        Text(
                                            text = "${contact.name} is ${stringResource(R.string.status_typing)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = EmeraldMid
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Banned or blocked contact notice
        AnimatedVisibility(visible = isMessagingDisabled) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("banned_contact_notice"),
                shape = RoundedCornerShape(12.dp),
                color = ErrorContainerLight
            ) {
                Text(
                    text = if (isContactBanned) {
                        "This account has reached $BAN_REPORT_LIMIT reports and is banned."
                    } else {
                        "This user is blocked. Unblock from the chat menu to send messages."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = ErrorRed,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        // Three Short Context-Aware Gemini Smart Reply Buttons Above Input
        AnimatedVisibility(
            visible = smartAiEnabled && !isMessagingDisabled && (quickSuggestions.isNotEmpty() || isGeneratingSuggestions)
        ) {
            SmartReplyButtonsBar(
                suggestions = quickSuggestions,
                isGenerating = isGeneratingSuggestions,
                onSelectSuggestion = { suggestion ->
                    messageText = suggestion
                    onDismissSuggestions()
                },
                onRefresh = onRequestSuggestions
            )
        }

        // Message Composer Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (messageText.isEmpty()) {
                            Text(
                                text = when {
                                    isContactBanned -> "Messaging disabled (account banned)"
                                    isContactBlocked -> "Messaging disabled (user blocked)"
                                    else -> stringResource(R.string.message_placeholder)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = messageText,
                            onValueChange = { if (!isMessagingDisabled) messageText = it },
                            enabled = !isMessagingDisabled,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(EmeraldMid),
                            maxLines = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("message_input")
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (messageText.isNotBlank() && !isMessagingDisabled) EmeraldDark
                            else EmeraldMid.copy(alpha = 0.45f)
                        )
                        .clickable(enabled = messageText.isNotBlank() && !isMessagingDisabled) {
                            val toSend = messageText
                            messageText = ""
                            onSendMessage(toSend)
                        }
                        .testTag("send_message_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showProfileDialog) {
        ContactProfileDialog(
            contact = contact,
            onDismiss = { showProfileDialog = false },
            onToggleBlock = {
                showProfileDialog = false
                onToggleBlockContact()
            },
            onReportClick = {
                showProfileDialog = false
                showReportDialog = true
            }
        )
    }

    if (showReportDialog) {
        ReportAccountDialog(
            contact = contact,
            onDismiss = { showReportDialog = false },
            onConfirmReport = {
                showReportDialog = false
                onReportContact()
            }
        )
    }
}

@Composable
private fun EndToEndEncryptionBanner(contactName: String) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val containerColor = if (isDark) {
        EmeraldDeep.copy(alpha = 0.45f)
    } else {
        MintLight.copy(alpha = 0.92f)
    }
    val borderColor = if (isDark) {
        EmeraldBright.copy(alpha = 0.3f)
    } else {
        EmeraldMid.copy(alpha = 0.25f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .testTag("e2e_encryption_banner"),
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(EmeraldMid.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.e2e_badge),
                        tint = if (isDark) MintOnlineGlow else EmeraldDark,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.e2e_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isDark) MintOnlineGlow else EmeraldDeep,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.e2e_banner_desc, contactName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartReplyButtonsBar(
    suggestions: List<String>,
    isGenerating: Boolean,
    onSelectSuggestion: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val visibleSuggestions = suggestions.take(3)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_reply_bar"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Compact refresh button
            Surface(
                modifier = Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 38.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !isGenerating, onClick = onRefresh)
                    .testTag("generate_suggestions_chip"),
                shape = CircleShape,
                color = MintLight
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = EmeraldDark
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh smart replies",
                            tint = EmeraldDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isGenerating && visibleSuggestions.isEmpty()) {
                repeat(3) { idx ->
                    Surface(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 38.dp)
                            .testTag("smart_reply_loading_$idx"),
                        shape = RoundedCornerShape(19.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, EmeraldMid.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = EmeraldMid,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (idx == 0) "Thinking…" else "Smart reply…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                visibleSuggestions.forEachIndexed { index, suggestion ->
                    Surface(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .clickable { onSelectSuggestion(suggestion) }
                            .testTag("smart_reply_button_$index"),
                        shape = RoundedCornerShape(19.dp),
                        color = MintLight,
                        border = BorderStroke(1.dp, EmeraldMid.copy(alpha = 0.35f)),
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = EmeraldDark,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldDeep,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingPulseDots(
    dotColor: Color,
    dotSize: androidx.compose.ui.unit.Dp
) {
    val transition = rememberInfiniteTransition(label = "typingDots")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) { index ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 550,
                        delayMillis = index * 140,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .offset(y = (-2.5f * progress).dp)
                    .alpha(0.45f + 0.55f * progress)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isMe = msg.sender == "me"
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val bubbleColor = when {
        isMe && isDark -> DarkBubbleMe
        isMe -> MintBubbleMe
        else -> MaterialTheme.colorScheme.surface
    }
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = bubbleShape,
            color = bubbleColor,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (msg.aiGenerated) {
                        Text(
                            text = "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldMid,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = msg.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

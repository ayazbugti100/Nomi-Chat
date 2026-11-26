package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.VoiceNoteManager
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThemeStyle
import com.example.data.model.ChatThread
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.network.NetworkStatus
import com.example.ui.components.AvatarCircle
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.theme.DangerRed
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.EmeraldVibrant
import com.example.ui.theme.ErrorContainerLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(
    contact: ChatThread,
    messagesState: UiState<List<ChatMessage>>,
    isContactTyping: Boolean,
    smartAiEnabled: Boolean,
    quickSuggestions: List<String>,
    isGeneratingSuggestions: Boolean,
    replyingToMessage: ChatMessage? = null,
    onReplyMessage: (ChatMessage?) -> Unit = {},
    onDeleteMessage: (ChatMessage) -> Unit = {},
    onUpdateTyping: (Boolean) -> Unit = {},
    onToggleSmartAi: (Boolean) -> Unit,
    onRequestSuggestions: () -> Unit,
    onDismissSuggestions: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendVoiceMessage: (String, Long) -> Unit = { _, _ -> },
    onSendImageUri: (Uri) -> Unit = {},
    onSendCameraBitmap: (Bitmap) -> Unit = {},
    onSendFileUri: (Uri) -> Unit = {},
    onVoiceCallInitiated: () -> Unit = {},
    onReportContact: (String) -> Unit,
    onToggleBlockContact: () -> Unit = {},
    onToggleArchiveContact: () -> Unit = {},
    onToggleMuteContact: () -> Unit = {},
    onSetDisappearingMessages: (Long) -> Unit = {},
    onSetChatTheme: (String) -> Unit = {},
    onToggleLockChat: () -> Unit = {},
    onClearMessages: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    onShowNotice: (String) -> Unit = {},
    onBack: () -> Unit,
    currentUserProfile: UserProfile? = null,
    networkStatus: NetworkStatus = NetworkStatus(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceNoteManager = remember(context) { VoiceNoteManager(context, scope) }
    val recordingState by voiceNoteManager.recordingState.collectAsStateWithLifecycle()
    val playbackState by voiceNoteManager.playbackState.collectAsStateWithLifecycle()

    DisposableEffect(voiceNoteManager) {
        onDispose { voiceNoteManager.release() }
    }

    var messageText by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showAttachmentDialog by remember { mutableStateOf(false) }
    var showDisappearingDialog by remember { mutableStateOf(false) }
    var showChatThemeDialog by remember { mutableStateOf(false) }
    var showMoreOptionsDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showInAppCallDialog by remember { mutableStateOf(false) }
    var previewImageData by remember { mutableStateOf<String?>(null) }

    var showInChatSearch by remember { mutableStateOf(false) }
    var inChatSearchQuery by remember { mutableStateOf("") }
    var currentSearchMatchIdx by remember { mutableIntStateOf(0) }

    BackHandler {
        when {
            recordingState.isRecording -> voiceNoteManager.cancelRecording()
            showEmojiPicker -> showEmojiPicker = false
            showInChatSearch -> {
                showInChatSearch = false
                inChatSearchQuery = ""
            }
            else -> onBack()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSendImageUri(uri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onSendFileUri(uri)
        }
    }

    val cameraPreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            onSendCameraBitmap(bitmap)
        }
    }

    val voicePermRationale = stringResource(R.string.voice_permission_rationale)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val started = voiceNoteManager.startRecording { maxClip ->
                onSendVoiceMessage(maxClip.mediaData, maxClip.durationSec)
            }
            if (!started) {
                onShowNotice("Unable to start microphone recording.")
            }
        } else {
            onShowNotice(voicePermRationale)
        }
    }

    val listState = rememberLazyListState()
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val chatTheme = remember(contact.chatTheme) { ChatThemeStyle.fromKey(contact.chatTheme) }
    val palette = rememberChatThemePalette(style = chatTheme, isDark = isDark)

    val isContactBanned = contact.accountStatus == "banned"
    val isContactBlocked = contact.blocked
    val isMessagingDisabled = isContactBanned || isContactBlocked
    val otherPartyTyping = (contact.typing || isContactTyping) && !isMessagingDisabled
    val messages = (messagesState as? UiState.Success)?.data.orEmpty()

    val searchMatchIndices = remember(messages, inChatSearchQuery, showInChatSearch) {
        val q = inChatSearchQuery.trim().lowercase()
        if (!showInChatSearch || q.isEmpty()) emptyList()
        else messages.mapIndexedNotNull { idx, msg ->
            if (msg.text.lowercase().contains(q)) idx else null
        }
    }

    LaunchedEffect(searchMatchIndices) {
        currentSearchMatchIdx = if (searchMatchIndices.isNotEmpty()) searchMatchIndices.lastIndex else 0
    }

    LaunchedEffect(currentSearchMatchIdx, searchMatchIndices, showInChatSearch) {
        if (showInChatSearch && searchMatchIndices.isNotEmpty()) {
            val msgIdx = searchMatchIndices[currentSearchMatchIdx.coerceIn(0, searchMatchIndices.lastIndex)]
            listState.animateScrollToItem(msgIdx)
        }
    }

    LaunchedEffect(messages.size, otherPartyTyping) {
        if (messages.isNotEmpty() && !showInChatSearch) {
            val targetIndex = if (otherPartyTyping) messages.size else messages.lastIndex
            listState.animateScrollToItem(targetIndex)
        }
    }

    val headerTextShadow = Shadow(
        color = Color.Black.copy(alpha = 0.72f),
        offset = Offset(0f, 1.5f),
        blurRadius = 4f
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.backgroundBase)
            .imePadding()
    ) {
        // WhatsApp-style Top Header with Live Contact Banner
        EmeraldWaveHeader(
            modifier = Modifier.fillMaxWidth(),
            bannerData = contact.bannerData
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
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
                        photoData = contact.photoData,
                        isGroup = contact.threadType == "group",
                        showOnlineDot = contact.online && !isContactBanned && !isContactBlocked,
                        size = 40.dp
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.titleMedium.copy(shadow = headerTextShadow),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (contact.muted) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsOff,
                                    contentDescription = "Muted",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (contact.disappearingSeconds > 0L) {
                                Icon(
                                    imageVector = Icons.Outlined.Timer,
                                    contentDescription = "Disappearing messages active",
                                    tint = MintOnlineGlow,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
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

                        if (otherPartyTyping) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.testTag("header_typing_indicator")
                            ) {
                                TypingPulseDots(dotColor = MintOnlineGlow, dotSize = 5.dp)
                                Text(
                                    text = stringResource(R.string.status_typing),
                                    style = MaterialTheme.typography.labelSmall.copy(shadow = headerTextShadow),
                                    color = MintOnlineGlow,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Text(
                                text = when {
                                    isContactBanned -> "Account banned"
                                    isContactBlocked -> "Blocked user"
                                    contact.online -> "Online"
                                    else -> "Offline"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(shadow = headerTextShadow),
                                color = MintOnlineGlow,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Voice Call Icon Button
                IconButton(
                    onClick = {
                        when {
                            isMessagingDisabled -> onShowNotice("Cannot call a blocked or banned user.")
                            !networkStatus.isConnected -> onShowNotice(NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE)
                            else -> {
                                onVoiceCallInitiated()
                                showInAppCallDialog = true
                            }
                        }
                    },
                    modifier = Modifier.testTag("header_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Voice call",
                        tint = Color.White
                    )
                }

                // Three-Dot (⋮) Overflow Menu Anchor
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.testTag("chat_more_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Chat options",
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .widthIn(min = 216.dp)
                            .testTag("chat_options_menu")
                    ) {
                        ChatMenuItem(
                            label = stringResource(R.string.menu_view_contact),
                            icon = Icons.Outlined.Person,
                            testTag = "menu_item_view_contact"
                        ) {
                            showOverflowMenu = false
                            showProfileDialog = true
                        }
                        ChatMenuItem(
                            label = stringResource(R.string.menu_search),
                            icon = Icons.Outlined.Search,
                            testTag = "menu_item_search"
                        ) {
                            showOverflowMenu = false
                            showInChatSearch = true
                        }
                        ChatMenuItem(
                            label = stringResource(R.string.menu_report),
                            icon = Icons.Outlined.Flag,
                            testTag = "menu_item_report"
                        ) {
                            showOverflowMenu = false
                            showReportDialog = true
                        }
                        ChatMenuItem(
                            label = stringResource(
                                if (contact.blocked) R.string.menu_unblock else R.string.menu_block
                            ),
                            icon = Icons.Outlined.Block,
                            tint = if (contact.blocked) EmeraldDark else DangerRed,
                            testTag = "menu_item_block"
                        ) {
                            showOverflowMenu = false
                            onToggleBlockContact()
                        }
                        ChatMenuItem(
                            label = stringResource(
                                if (contact.muted) R.string.menu_unmute_notifications
                                else R.string.menu_mute_notifications
                            ),
                            icon = if (contact.muted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                            testTag = "menu_item_mute"
                        ) {
                            showOverflowMenu = false
                            onToggleMuteContact()
                        }
                        ChatMenuItem(
                            label = stringResource(R.string.menu_disappearing_messages),
                            icon = Icons.Outlined.Timer,
                            testTag = "menu_item_disappearing"
                        ) {
                            showOverflowMenu = false
                            showDisappearingDialog = true
                        }
                        ChatMenuItem(
                            label = stringResource(R.string.menu_chat_theme),
                            icon = Icons.Outlined.Palette,
                            testTag = "menu_item_chat_theme"
                        ) {
                            showOverflowMenu = false
                            showChatThemeDialog = true
                        }
                        ChatMenuItem(
                            label = stringResource(
                                if (contact.archived) R.string.menu_unarchive_chat
                                else R.string.menu_archive_chat
                            ),
                            icon = if (contact.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            testTag = "menu_item_archive"
                        ) {
                            showOverflowMenu = false
                            onToggleArchiveContact()
                        }
                        ChatMenuItem(
                            label = stringResource(R.string.menu_delete_chat),
                            icon = Icons.Outlined.DeleteOutline,
                            tint = DangerRed,
                            testTag = "menu_item_delete_chat"
                        ) {
                            showOverflowMenu = false
                            showDeleteConfirmDialog = true
                        }
                        ChatMenuItem(
                            label = stringResource(R.string.menu_more),
                            icon = Icons.Outlined.MoreHoriz,
                            testTag = "menu_item_more"
                        ) {
                            showOverflowMenu = false
                            showMoreOptionsDialog = true
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showInChatSearch) {
            InChatSearchBar(
                query = inChatSearchQuery,
                matchCount = searchMatchIndices.size,
                currentMatchIndex = currentSearchMatchIdx,
                onQueryChange = { inChatSearchQuery = it },
                onPrevMatch = {
                    if (searchMatchIndices.isNotEmpty()) {
                        currentSearchMatchIdx =
                            (currentSearchMatchIdx - 1 + searchMatchIndices.size) % searchMatchIndices.size
                    }
                },
                onNextMatch = {
                    if (searchMatchIndices.isNotEmpty()) {
                        currentSearchMatchIdx = (currentSearchMatchIdx + 1) % searchMatchIndices.size
                    }
                },
                onClose = {
                    showInChatSearch = false
                    inChatSearchQuery = ""
                }
            )
        }

        // Doodle Wallpaper + Chat Messages Area
        Box(modifier = Modifier.weight(1f)) {
            Image(
                painter = painterResource(id = R.drawable.img_chat_doodle_wallpaper),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(palette.wallpaperAlpha)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.wallpaperOverlay.copy(alpha = if (isDark) 0.55f else 0.16f))
            )

            when (messagesState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = palette.accent)
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
                    val activeMatchMsgIndex = searchMatchIndices.getOrNull(currentSearchMatchIdx)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages.size, key = { idx -> messages[idx].id }) { idx ->
                            val msg = messages[idx]
                            MessageBubble(
                                msg = msg,
                                palette = palette,
                                playbackState = playbackState,
                                searchQuery = if (showInChatSearch) inChatSearchQuery else "",
                                isCurrentSearchMatch = showInChatSearch && activeMatchMsgIndex == idx,
                                senderName = if (msg.sender == "me") {
                                    currentUserProfile?.displayName?.takeIf { it.isNotBlank() } ?: "You"
                                } else {
                                    contact.name
                                },
                                senderPhotoData = if (msg.sender == "me") {
                                    currentUserProfile?.photoData
                                } else {
                                    contact.photoData
                                },
                                onToggleVoicePlayback = { target ->
                                    voiceNoteManager.togglePlayback(
                                        messageId = target.id,
                                        mediaData = target.mediaData,
                                        fallbackDurationSec = target.durationSec,
                                        onError = onShowNotice
                                    )
                                },
                                onSeekVoicePlayback = { target, fraction ->
                                    voiceNoteManager.seekToFraction(
                                        messageId = target.id,
                                        mediaData = target.mediaData,
                                        fallbackDurationSec = target.durationSec,
                                        fraction = fraction,
                                        onError = onShowNotice
                                    )
                                },
                                onPreviewImage = { data -> previewImageData = data },
                                onDeleteMessage = onDeleteMessage,
                                onReplyMessage = onReplyMessage
                            )
                        }

                        if (otherPartyTyping) {
                            item(key = "typing_bubble") {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 4.dp,
                                        topEnd = 14.dp,
                                        bottomEnd = 14.dp,
                                        bottomStart = 14.dp
                                    ),
                                    color = palette.bubbleThem,
                                    shadowElevation = 1.dp,
                                    modifier = Modifier.testTag("in_chat_typing_bubble")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TypingPulseDots(dotColor = palette.accent, dotSize = 6.dp)
                                        Text(
                                            text = "${contact.name} is ${stringResource(R.string.status_typing)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.accent
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
                        "This account has been banned."
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

        // Context-Aware Smart Reply Bar Above Input
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

        AnimatedVisibility(visible = showEmojiPicker && !isMessagingDisabled && !recordingState.isRecording) {
            EmojiQuickPickerBar(
                onEmojiSelected = { emoji -> messageText += emoji },
                onClose = { showEmojiPicker = false }
            )
        }

        AnimatedVisibility(visible = replyingToMessage != null && !isMessagingDisabled) {
            if (replyingToMessage != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${replyingToMessage.sender.let { if (it == "me") "You" else contact.name }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldMid,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = replyingToMessage.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onReplyMessage(null) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // WhatsApp-style Bottom Composer Bar (Emoji + Message + Pin + Camera + Mic/Send)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (recordingState.isRecording) {
                VoiceRecordingBar(
                    recordingState = recordingState,
                    onCancel = { voiceNoteManager.cancelRecording() },
                    onSend = {
                        val note = voiceNoteManager.stopAndEncode()
                        if (note != null) {
                            onSendVoiceMessage(note.mediaData, note.durationSec)
                        } else {
                            onShowNotice("Voice note was too short.")
                        }
                    }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(26.dp),
                        color = if (isDark) Color(0xFF1F2C34) else Color.White,
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (!isMessagingDisabled) showEmojiPicker = !showEmojiPicker
                                },
                                enabled = !isMessagingDisabled,
                                modifier = Modifier.testTag("composer_emoji_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.EmojiEmotions,
                                    contentDescription = "Emoji",
                                    tint = if (showEmojiPicker) EmeraldMid else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (messageText.isEmpty()) {
                                    Text(
                                        text = when {
                                            isContactBanned -> "Account banned"
                                            isContactBlocked -> "User blocked"
                                            else -> stringResource(R.string.message_placeholder)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = messageText,
                                    onValueChange = { 
                                        if (!isMessagingDisabled) {
                                            messageText = it
                                            onUpdateTyping(it.isNotBlank())
                                        }
                                    },
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

                            IconButton(
                                onClick = {
                                    if (!isMessagingDisabled) showAttachmentDialog = true
                                },
                                enabled = !isMessagingDisabled,
                                modifier = Modifier.testTag("composer_attach_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = "Attach media or file",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.rotate(35f)
                                )
                            }

                            if (messageText.isBlank()) {
                                IconButton(
                                    onClick = {
                                        if (!isMessagingDisabled) {
                                            runCatching { cameraPreviewLauncher.launch(null) }
                                                .onFailure {
                                                    photoPickerLauncher.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                    )
                                                }
                                        }
                                    },
                                    enabled = !isMessagingDisabled,
                                    modifier = Modifier.testTag("composer_camera_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoCamera,
                                        contentDescription = "Camera",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Green Circular Mic / Send Button
                    if (messageText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (!isMessagingDisabled) EmeraldVibrant else EmeraldMid.copy(alpha = 0.45f))
                                .clickable(enabled = !isMessagingDisabled) {
                                    val toSend = messageText
                                    if (networkStatus.isConnected) {
                                        messageText = ""
                                        showEmojiPicker = false
                                    }
                                    onSendMessage(toSend)
                                }
                                .testTag("send_message_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (!isMessagingDisabled) EmeraldVibrant else EmeraldMid.copy(alpha = 0.45f))
                                .pointerInput(isMessagingDisabled, networkStatus.isConnected) {
                                    if (isMessagingDisabled) return@pointerInput
                                    detectTapGestures(
                                        onPress = {
                                            if (!networkStatus.isConnected) {
                                                onShowNotice(NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE)
                                                return@detectTapGestures
                                            }
                                            val hasMicPerm = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!hasMicPerm) {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                return@detectTapGestures
                                            }
                                            showEmojiPicker = false
                                            val pressStart = System.currentTimeMillis()
                                            val started = voiceNoteManager.startRecording { maxClip ->
                                                onSendVoiceMessage(maxClip.mediaData, maxClip.durationSec)
                                            }
                                            if (!started) {
                                                onShowNotice("Unable to start microphone recording.")
                                                return@detectTapGestures
                                            }
                                            tryAwaitRelease()
                                            val heldMs = System.currentTimeMillis() - pressStart
                                            if (heldMs >= 900L && voiceNoteManager.recordingState.value.isRecording) {
                                                val note = voiceNoteManager.stopAndEncode()
                                                if (note != null) {
                                                    onSendVoiceMessage(note.mediaData, note.durationSec)
                                                } else {
                                                    onShowNotice("Voice note was too short.")
                                                }
                                            }
                                        }
                                    )
                                }
                                .testTag("voice_record_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record voice message",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
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
            onConfirmReport = { reason ->
                showReportDialog = false
                onReportContact(reason)
            }
        )
    }

    if (showAttachmentDialog) {
        AttachmentPickerDialog(
            onDismiss = { showAttachmentDialog = false },
            onPickPhoto = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickFile = {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    if (showDisappearingDialog) {
        DisappearingMessagesDialog(
            currentSeconds = contact.disappearingSeconds,
            onSelectDuration = onSetDisappearingMessages,
            onDismiss = { showDisappearingDialog = false }
        )
    }

    if (showChatThemeDialog) {
        ChatThemePickerDialog(
            currentThemeKey = contact.chatTheme,
            onSelectTheme = onSetChatTheme,
            onDismiss = { showChatThemeDialog = false }
        )
    }

    if (showMoreOptionsDialog) {
        MoreChatOptionsDialog(
            contact = contact,
            smartAiEnabled = smartAiEnabled,
            onToggleSmartAi = onToggleSmartAi,
            onRefreshSmartReplies = onRequestSuggestions,
            onToggleLockChat = onToggleLockChat,
            onClearMessages = { showClearConfirmDialog = true },
            onDismiss = { showMoreOptionsDialog = false }
        )
    }

    if (showClearConfirmDialog) {
        ConfirmDangerActionDialog(
            title = "Clear chat messages?",
            body = "All messages in this chat with ${contact.name} will be removed from your view.",
            confirmLabel = "Clear",
            onConfirm = onClearMessages,
            onDismiss = { showClearConfirmDialog = false }
        )
    }

    if (showDeleteConfirmDialog) {
        ConfirmDangerActionDialog(
            title = "Delete chat?",
            body = "This will permanently delete your chat and messages with ${contact.name}.",
            confirmLabel = "Delete",
            onConfirm = onDeleteChat,
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }

    if (showInAppCallDialog) {
        InAppVoiceCallDialog(
            contact = contact,
            onEndCall = { showInAppCallDialog = false }
        )
    }

    previewImageData?.let { img ->
        ImageMessagePreviewDialog(
            mediaData = img,
            onDismiss = { previewImageData = null }
        )
    }
}

@Composable
private fun ChatMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag)
    )
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

package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChatPillFilter
import com.example.data.model.ChatThread
import com.example.data.model.StoryItem
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.network.NetworkStatus
import com.example.ui.components.AvatarCircle
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.components.GoPremiumBanner
import com.example.ui.theme.BadgeBlockedBg
import com.example.ui.theme.BadgeBlockedText
import com.example.ui.theme.BadgeLockedBg
import com.example.ui.theme.BadgeLockedText
import com.example.ui.theme.DangerRed
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.EmeraldVibrant
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LightOnSurface
import kotlin.math.max

@Composable
fun ChatsScreen(
    contactsState: UiState<List<ChatThread>>,
    archivedChats: List<ChatThread> = emptyList(),
    profile: UserProfile? = null,
    activeStories: List<StoryItem> = emptyList(),
    selectedFilter: ChatPillFilter,
    isSearchVisible: Boolean,
    searchQuery: String,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (ChatPillFilter) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPrivacySettings: () -> Unit = {},
    onOpenArchivedFolder: () -> Unit = {},
    onOpenPremium: () -> Unit,
    onOpenChat: (String) -> Unit,
    onAvatarClick: (ChatThread) -> Unit = {},
    onRenameChat: (ChatThread) -> Unit = {},
    onViewContact: (ChatThread) -> Unit = {},
    onToggleLockChat: (ChatThread) -> Unit = {},
    onToggleBlockChat: (ChatThread) -> Unit,
    onToggleArchiveChat: (ChatThread) -> Unit = {},
    onDeleteChat: (ChatThread) -> Unit,
    onAddContactClick: () -> Unit,
    onPostStoryClick: () -> Unit = {},
    onOpenStoryGroup: (List<StoryItem>) -> Unit = {},
    networkStatus: NetworkStatus = NetworkStatus(),
    modifier: Modifier = Modifier
) {
    val myUserId = profile?.userId.orEmpty()
    val storiesByAuthor = remember(activeStories) {
        activeStories.groupBy { it.userId.ifBlank { it.authorUsername } }
    }
    val myStories = remember(storiesByAuthor, myUserId, profile?.username) {
        activeStories.filter { story ->
            (myUserId.isNotBlank() && story.userId == myUserId) ||
                (!profile?.username.isNullOrBlank() && story.authorUsername.equals(profile?.username, ignoreCase = true))
        }
    }
    val activeStoryAuthorUids = remember(activeStories) {
        activeStories.mapNotNull { it.userId.takeIf(String::isNotBlank) }.toSet()
    }
    val activeStoryUsernames = remember(activeStories) {
        activeStories.mapNotNull { it.authorUsername.takeIf(String::isNotBlank)?.lowercase() }.toSet()
    }
    fun threadHasActiveStory(thread: ChatThread): Boolean {
        return (thread.peerUid.isNotBlank() && activeStoryAuthorUids.contains(thread.peerUid)) ||
            (thread.username.isNotBlank() && activeStoryUsernames.contains(thread.username.lowercase()))
    }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EmeraldWaveHeader(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldVibrant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onToggleSearch,
                                modifier = Modifier.testTag("search_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = "Search chats",
                                    tint = Color.White
                                )
                            }
                            var topMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { topMenuExpanded = true },
                                    modifier = Modifier.testTag("header_profile_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = topMenuExpanded,
                                    onDismissRequest = { topMenuExpanded = false },
                                    containerColor = Color.White,
                                    shape = RoundedCornerShape(16.dp),
                                    shadowElevation = 10.dp,
                                    modifier = Modifier
                                        .width(215.dp)
                                        .testTag("home_more_dropdown_menu")
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.privacy_settings_title),
                                                color = LightOnSurface,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = EmeraldDark,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            onOpenPrivacySettings()
                                        },
                                        modifier = Modifier.testTag("menu_privacy_settings")
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Profile & Settings",
                                                color = LightOnSurface,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = LightOnSurface,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            onOpenProfile()
                                        },
                                        modifier = Modifier.testTag("menu_profile_settings")
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Add Chat by @username",
                                                color = LightOnSurface,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = EmeraldMid,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            onAddContactClick()
                                        },
                                        modifier = Modifier.testTag("menu_add_chat")
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = isSearchVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, end = 8.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color.White.copy(alpha = 0.22f))
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                cursorBrush = SolidColor(Color.White),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("search_input")
                            )
                        }
                    }
                }
            }

            // Curved white/surface sheet overlapping the wave header
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = (-14).dp),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FilterPillsRow(
                        selectedFilter = selectedFilter,
                        onSelectFilter = onSelectFilter,
                        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp)
                    )

                    GoPremiumBanner(
                        onClick = onOpenPremium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    AnimatedVisibility(visible = !networkStatus.isConnected) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("offline_status_banner"),
                            shape = RoundedCornerShape(12.dp),
                            color = BadgeLockedBg
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "Offline",
                                    tint = BadgeLockedText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Offline • Will auto-sync when Wi-Fi or mobile data connects",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BadgeLockedText,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (selectedFilter == ChatPillFilter.CHATS && activeStories.isNotEmpty()) {
                        StoriesStripRow(
                            profile = profile,
                            myStories = myStories,
                            allStories = activeStories,
                            onPostStoryClick = onPostStoryClick,
                            onOpenStoryGroup = onOpenStoryGroup
                        )
                    }

                    when (selectedFilter) {
                        ChatPillFilter.STATUS -> {
                            StatusOverviewList(
                                profile = profile,
                                myStories = myStories,
                                allStories = activeStories,
                                contacts = (contactsState as? UiState.Success)?.data.orEmpty(),
                                threadHasStory = ::threadHasActiveStory,
                                onPostStoryClick = onPostStoryClick,
                                onOpenStoryGroup = onOpenStoryGroup,
                                onOpenChat = onOpenChat
                            )
                        }
                        ChatPillFilter.CALLS -> {
                            CallsOverviewList(
                                contacts = (contactsState as? UiState.Success)?.data.orEmpty(),
                                threadHasStory = ::threadHasActiveStory,
                                onOpenChat = onOpenChat
                            )
                        }
                        else -> {
                            when (contactsState) {
                                is UiState.Loading -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
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
                                            text = contactsState.message,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                is UiState.Success -> {
                                    val showArchivedEntry = selectedFilter == ChatPillFilter.CHATS && archivedChats.isNotEmpty()
                                    if (contactsState.data.isEmpty() && !showArchivedEntry) {
                                        EmptyChatsState(onAddContactClick = onAddContactClick)
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(
                                                start = 10.dp,
                                                end = 10.dp,
                                                top = 4.dp,
                                                bottom = 104.dp
                                            )
                                        ) {
                                            if (showArchivedEntry) {
                                                item(key = "archived_folder_row") {
                                                    ArchivedFolderRow(
                                                        archivedChats = archivedChats,
                                                        onClick = onOpenArchivedFolder
                                                    )
                                                }
                                            }
                                            if (contactsState.data.isEmpty() && showArchivedEntry) {
                                                item(key = "all_chats_archived_hint") {
                                                    Text(
                                                        text = "Your main inbox is clean. Tap Archived above to view or unarchive chats.",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 24.dp, vertical = 28.dp)
                                                            .testTag("inbox_archived_hint")
                                                    )
                                                }
                                            }
                                            items(contactsState.data, key = { it.id }) { thread ->
                                                ChatListRow(
                                                    thread = thread,
                                                    hasActiveStory = threadHasActiveStory(thread),
                                                    onOpenChat = { onOpenChat(thread.id) },
                                                    onAvatarClick = { onAvatarClick(thread) },
                                                    onRenameChat = { onRenameChat(thread) },
                                                    onViewContact = { onViewContact(thread) },
                                                    onToggleArchive = { onToggleArchiveChat(thread) },
                                                    onToggleBlock = { onToggleBlockChat(thread) },
                                                    onDeleteChat = { onDeleteChat(thread) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating pen FAB matching screenshot
        FloatingActionButton(
            onClick = onAddContactClick,
            containerColor = EmeraldDark,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 22.dp)
                .size(52.dp)
                .testTag("fab_add_contact")
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "New contact or message"
            )
        }
    }
}

@Composable
private fun FilterPillsRow(
    selectedFilter: ChatPillFilter,
    onSelectFilter: (ChatPillFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPillChip(
            label = "Chats",
            icon = Icons.AutoMirrored.Filled.Chat,
            selected = selectedFilter == ChatPillFilter.CHATS,
            testTag = "pill_chats",
            onClick = { onSelectFilter(ChatPillFilter.CHATS) }
        )
        FilterPillChip(
            label = "Groups",
            icon = Icons.Default.Group,
            selected = selectedFilter == ChatPillFilter.GROUPS,
            testTag = "pill_groups",
            onClick = { onSelectFilter(ChatPillFilter.GROUPS) }
        )
        FilterPillChip(
            label = "Status",
            icon = Icons.Default.DonutLarge,
            selected = selectedFilter == ChatPillFilter.STATUS,
            testTag = "pill_status",
            onClick = { onSelectFilter(ChatPillFilter.STATUS) }
        )
        FilterPillChip(
            label = "Calls",
            icon = Icons.Default.Call,
            selected = selectedFilter == ChatPillFilter.CALLS,
            testTag = "pill_calls",
            onClick = { onSelectFilter(ChatPillFilter.CALLS) }
        )
    }
}

@Composable
private fun FilterPillChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bgColor = if (selected) EmeraldDark else MaterialTheme.colorScheme.surfaceVariant
    val fgColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(22.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fgColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun ArchivedFolderRow(
    archivedChats: List<ChatThread>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unreadArchivedCount = archivedChats.count { !it.blocked && it.unreadCount > 0L }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("archived_folder_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(EmeraldMid.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Archive,
                contentDescription = stringResource(R.string.archived_folder_title),
                tint = EmeraldDark,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.archived_folder_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            val previewNames = archivedChats.take(3).joinToString(", ") { it.name }
            if (previewNames.isNotBlank()) {
                Text(
                    text = previewNames,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (unreadArchivedCount > 0) EmeraldBright else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 9.dp, vertical = 3.dp)
                .testTag("archived_folder_count"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = archivedChats.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (unreadArchivedCount > 0) Color.White else EmeraldDark
            )
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        modifier = Modifier.padding(horizontal = 14.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListRow(
    thread: ChatThread,
    hasActiveStory: Boolean = false,
    showQuickUnarchive: Boolean = false,
    onOpenChat: () -> Unit,
    onAvatarClick: () -> Unit,
    onRenameChat: () -> Unit,
    onViewContact: () -> Unit,
    onToggleArchive: () -> Unit = {},
    onToggleBlock: () -> Unit,
    onDeleteChat: () -> Unit
) {
    val isChannel = thread.name.contains("Channel", ignoreCase = true)
    val isGroup = thread.threadType == "group" && !isChannel
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (thread.blocked) 0.65f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onOpenChat,
                    onLongClick = { menuExpanded = true },
                    onDoubleClick = { menuExpanded = true }
                )
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .testTag("chat_item_${thread.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick)
                    .testTag("chat_avatar_${thread.id}")
            ) {
                AvatarCircle(
                    name = thread.name,
                    photoData = thread.photoData,
                    isGroup = isGroup,
                    isChannel = isChannel,
                    showOnlineDot = thread.online && !thread.blocked,
                    hasStoryRing = hasActiveStory && !thread.blocked,
                    size = 52.dp
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = thread.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (thread.locked) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BadgeLockedBg,
                                modifier = Modifier.testTag("chat_locked_badge_${thread.id}")
                            ) {
                                Text(
                                    text = "Locked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BadgeLockedText,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (thread.blocked) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BadgeBlockedBg,
                                modifier = Modifier.testTag("chat_blocked_badge_${thread.id}")
                            ) {
                                Text(
                                    text = "Blocked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BadgeBlockedText,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (thread.accountStatus == "banned") {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ErrorRed.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = stringResource(R.string.banned_account_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ErrorRed,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = thread.lastMessageTimeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thread.unreadCount > 0L) EmeraldMid else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (thread.unreadCount > 0L) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when {
                            thread.locked -> {
                                Text(
                                    text = "🔒 Chat locked",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            thread.blocked -> {
                                Text(
                                    text = "Blocked user",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BadgeBlockedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            thread.typing && thread.accountStatus != "banned" -> {
                                Text(
                                    text = stringResource(R.string.status_typing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EmeraldMid,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            else -> {
                                if (thread.lastMessageFromMe) {
                                    Icon(
                                        imageVector = Icons.Default.DoneAll,
                                        contentDescription = "Read",
                                        tint = Color(0xFF1E9BF0),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                val messageBody = thread.lastMessageText.ifBlank { "Tap to start chatting" }
                                val previewText = if (thread.username.isNotBlank()) {
                                    "@${thread.username} • $messageBody"
                                } else {
                                    messageBody
                                }
                                Text(
                                    text = previewText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (thread.unreadCount > 0L && !thread.blocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(EmeraldBright)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                .testTag("unread_badge_${thread.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (thread.unreadCount > 99L) "99+" else thread.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (showQuickUnarchive && thread.archived) {
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onToggleArchive,
                            modifier = Modifier.testTag("quick_unarchive_button_${thread.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Unarchive,
                                contentDescription = stringResource(R.string.menu_unarchive_chat),
                                tint = EmeraldDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Popup menu anchored to the double-tapped or long-pressed chat row
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 10.dp,
            modifier = Modifier
                .width(195.dp)
                .testTag("chat_popup_menu_${thread.id}")
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Rename Chat",
                        color = LightOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = EmeraldMid,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onRenameChat()
                },
                modifier = Modifier.testTag("popup_rename_chat_${thread.id}")
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "View Contact",
                        color = LightOnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = LightOnSurface,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onViewContact()
                },
                modifier = Modifier.testTag("popup_view_contact_${thread.id}")
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (thread.archived) "Unarchive" else "Archive",
                        color = LightOnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (thread.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = null,
                        tint = EmeraldDark,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onToggleArchive()
                },
                modifier = Modifier.testTag("popup_archive_chat_${thread.id}")
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (thread.blocked) "Unblock" else "Block",
                        color = LightOnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = LightOnSurface,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onToggleBlock()
                },
                modifier = Modifier.testTag("popup_block_chat_${thread.id}")
            )
            HorizontalDivider(color = Color(0xFFECEFF1), thickness = 0.8.dp)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Delete",
                        color = DangerRed,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = DangerRed,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDeleteChat()
                },
                modifier = Modifier.testTag("popup_delete_chat_${thread.id}")
            )
        }
    }
}

@Composable
private fun StoriesStripRow(
    profile: UserProfile?,
    myStories: List<StoryItem>,
    allStories: List<StoryItem>,
    onPostStoryClick: () -> Unit,
    onOpenStoryGroup: (List<StoryItem>) -> Unit
) {
    val myUserId = profile?.userId.orEmpty()
    val peerGroups = remember(allStories, myUserId, profile?.username) {
        allStories
            .filterNot { story ->
                (myUserId.isNotBlank() && story.userId == myUserId) ||
                    (!profile?.username.isNullOrBlank() && story.authorUsername.equals(profile?.username, ignoreCase = true))
            }
            .groupBy { it.userId.ifBlank { it.authorUsername } }
            .values
            .toList()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("stories_strip_row"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .width(62.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    if (myStories.isNotEmpty()) onOpenStoryGroup(myStories) else onPostStoryClick()
                }
                .testTag("my_story_strip_item"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                AvatarCircle(
                    name = profile?.displayName?.ifBlank { "My Story" } ?: "My Story",
                    photoData = profile?.photoData,
                    hasStoryRing = myStories.isNotEmpty(),
                    size = 54.dp
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(EmeraldDark)
                        .clickable(onClick = onPostStoryClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add 24-hour story",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "My Story",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        peerGroups.forEach { group ->
            val first = group.firstOrNull() ?: return@forEach
            Column(
                modifier = Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenStoryGroup(group) }
                    .testTag("peer_story_item_${first.authorUsername}"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AvatarCircle(
                    name = first.authorName,
                    photoData = first.authorPhotoData,
                    hasStoryRing = true,
                    size = 54.dp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = first.authorName.substringBefore(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StatusOverviewList(
    profile: UserProfile?,
    myStories: List<StoryItem>,
    allStories: List<StoryItem>,
    contacts: List<ChatThread>,
    threadHasStory: (ChatThread) -> Boolean,
    onPostStoryClick: () -> Unit,
    onOpenStoryGroup: (List<StoryItem>) -> Unit,
    onOpenChat: (String) -> Unit
) {
    val myUserId = profile?.userId.orEmpty()
    val peerStoryGroups = remember(allStories, myUserId, profile?.username) {
        allStories
            .filterNot { story ->
                (myUserId.isNotBlank() && story.userId == myUserId) ||
                    (!profile?.username.isNullOrBlank() && story.authorUsername.equals(profile?.username, ignoreCase = true))
            }
            .groupBy { it.userId.ifBlank { it.authorUsername } }
            .values
            .toList()
    }
    val activeStatuses = contacts.filter { it.online }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "24-Hour Stories",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Button(
                    onClick = onPostStoryClick,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("status_add_story_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Story", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        if (myStories.isNotEmpty()) onOpenStoryGroup(myStories) else onPostStoryClick()
                    }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AvatarCircle(
                    name = profile?.displayName?.ifBlank { "My Story" } ?: "My Story",
                    photoData = profile?.photoData,
                    hasStoryRing = myStories.isNotEmpty(),
                    size = 50.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "My Story",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (myStories.isNotEmpty()) {
                            "${myStories.size} active • Tap to view (24h)"
                        } else {
                            "Tap '+' or here to share a 24h photo story"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(peerStoryGroups, key = { it.first().id }) { group ->
            val first = group.first()
            val remainingHrs = max(1, ((first.expiresAtMs - System.currentTimeMillis()) / 3_600_000L).toInt())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenStoryGroup(group) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AvatarCircle(
                    name = first.authorName,
                    photoData = first.authorPhotoData,
                    hasStoryRing = true,
                    size = 50.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = first.authorName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${group.size} story update • ${remainingHrs}h left",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "Online Contacts",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        if (activeStatuses.isEmpty()) {
            item {
                Text(
                    text = "No contacts are currently online.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        items(activeStatuses, key = { it.id }) { thread ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenChat(thread.id) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AvatarCircle(
                    name = thread.name,
                    photoData = thread.photoData,
                    isGroup = thread.threadType == "group",
                    borderColor = if (threadHasStory(thread)) null else EmeraldBright,
                    hasStoryRing = threadHasStory(thread),
                    showOnlineDot = true,
                    size = 50.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thread.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Online • Tap to send a quick message",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CallsOverviewList(
    contacts: List<ChatThread>,
    threadHasStory: (ChatThread) -> Boolean,
    onOpenChat: (String) -> Unit
) {
    val callEntries = contacts.filter { it.threadType == "chat" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Recent Calls",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        items(callEntries, key = { it.id }) { thread ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenChat(thread.id) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AvatarCircle(
                    name = thread.name,
                    photoData = thread.photoData,
                    hasStoryRing = threadHasStory(thread),
                    showOnlineDot = thread.online,
                    size = 48.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thread.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (thread.unreadCount > 0L) {
                                Icons.AutoMirrored.Filled.CallReceived
                            } else {
                                Icons.AutoMirrored.Filled.CallMade
                            },
                            contentDescription = null,
                            tint = EmeraldMid,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${thread.phone} • ${thread.lastMessageTimeLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onOpenChat(thread.id) }) {
                    Icon(
                        imageVector = if (thread.online) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = "Open chat with ${thread.name}",
                        tint = EmeraldDark
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatsState(onAddContactClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("empty_chats_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = EmeraldMid.copy(alpha = 0.55f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No chats yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Add a contact by @username to start messaging.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAddContactClick,
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.testTag("empty_add_contact_button")
        ) {
            Text(text = "Add by @username", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

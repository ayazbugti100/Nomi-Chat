package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChatThread
import com.example.data.model.StoryItem
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    archivedChats: List<ChatThread>,
    activeStories: List<StoryItem> = emptyList(),
    seenStoryIds: Set<String> = emptySet(),
    onOpenChat: (String) -> Unit,
    onAvatarClick: (ChatThread) -> Unit,
    onRenameChat: (ChatThread) -> Unit,
    onViewContact: (ChatThread) -> Unit,
    onUnarchiveChat: (ChatThread) -> Unit,
    onToggleBlockChat: (ChatThread) -> Unit,
    onDeleteChat: (ChatThread) -> Unit,
    onBack: () -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    fun storiesForThread(thread: ChatThread): List<StoryItem> {
        return activeStories.filter { story ->
            (thread.peerUid.isNotBlank() && story.userId == thread.peerUid) ||
                (thread.username.isNotBlank() && story.authorUsername.equals(thread.username, ignoreCase = true))
        }
    }

    fun threadHasActiveStory(thread: ChatThread): Boolean = storiesForThread(thread).isNotEmpty()
    fun threadStorySeen(thread: ChatThread): Boolean {
        val list = storiesForThread(thread)
        return list.isNotEmpty() && list.all { it.id in seenStoryIds }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        EmeraldWaveHeader(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("archived_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to chats",
                        tint = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.archived_folder_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${archivedChats.size} archived ${if (archivedChats.size == 1) "chat" else "chats"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MintOnlineGlow
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-14).dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (archivedChats.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(28.dp)
                            .testTag("archived_empty_state"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MintLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    tint = EmeraldDark,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Text(
                                text = "No archived chats",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Long-press a chat or open its menu to archive it and keep your inbox tidy.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("archived_chats_list"),
                        contentPadding = PaddingValues(
                            start = 10.dp,
                            end = 10.dp,
                            top = 12.dp,
                            bottom = 28.dp
                        )
                    ) {
                        item(key = "archived_banner_note") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = EmeraldMid.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "Archived chats stay hidden from your main inbox. Tap the unarchive icon or hold a chat to restore it.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldDark,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        items(archivedChats, key = { it.id }) { thread ->
                            ChatListRow(
                                thread = thread,
                                hasActiveStory = threadHasActiveStory(thread),
                                storyRingSeen = threadStorySeen(thread),
                                showQuickUnarchive = true,
                                onOpenChat = { onOpenChat(thread.id) },
                                onAvatarClick = { onAvatarClick(thread) },
                                onRenameChat = { onRenameChat(thread) },
                                onViewContact = { onViewContact(thread) },
                                onToggleArchive = { onUnarchiveChat(thread) },
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

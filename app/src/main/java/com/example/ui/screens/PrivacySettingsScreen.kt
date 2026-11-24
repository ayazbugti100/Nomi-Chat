package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChatThread
import com.example.data.model.ProfileVisibilityMode
import com.example.data.model.UserProfile
import com.example.ui.components.AvatarCircle
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.theme.BadgeBlockedBg
import com.example.ui.theme.BadgeBlockedText
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.ErrorContainerLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow

@Composable
fun PrivacySettingsScreen(
    profile: UserProfile?,
    allThreads: List<ChatThread>,
    blockedContacts: List<ChatThread>,
    onSavePrivacySettings: (
        visibility: ProfileVisibilityMode,
        allowedViewerIds: List<String>,
        maxViewers: Long
    ) -> Unit,
    onUnblockContact: (ChatThread) -> Unit,
    onShowNotice: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showBlockedList by remember { mutableStateOf(false) }

    val serverMode = ProfileVisibilityMode.fromKey(profile?.profileVisibility)
    val serverAllowed = profile?.allowedViewerUids.orEmpty()
    val serverMax = (profile?.maxProfileViewers ?: 0L).coerceIn(0L, 500L)

    var selectedMode by remember(serverMode) { mutableStateOf(serverMode) }
    var allowedIds by remember(serverAllowed) { mutableStateOf(serverAllowed) }
    var maxViewers by remember(serverMax) { mutableLongStateOf(serverMax) }

    LaunchedEffect(serverMode, serverAllowed, serverMax) {
        selectedMode = serverMode
        allowedIds = serverAllowed
        maxViewers = serverMax
    }

    BackHandler {
        if (showBlockedList) {
            showBlockedList = false
        } else {
            onBack()
        }
    }

    val directContacts = remember(allThreads) {
        allThreads.filter { it.threadType == "chat" }
    }

    fun contactViewerKey(contact: ChatThread): String {
        return contact.peerUid.ifBlank { contact.username.trim().lowercase() }
    }

    fun isContactAllowed(contact: ChatThread): Boolean {
        val key = contactViewerKey(contact)
        val handle = contact.username.trim().lowercase()
        return allowedIds.any {
            (key.isNotBlank() && it == key) || (handle.isNotBlank() && it.equals(handle, ignoreCase = true))
        }
    }

    fun commitSettings(
        nextMode: ProfileVisibilityMode = selectedMode,
        nextAllowed: List<String> = allowedIds,
        nextMax: Long = maxViewers
    ) {
        val cleanMax = nextMax.coerceIn(0L, 500L)
        val trimmedAllowed = if (cleanMax > 0L && nextAllowed.size > cleanMax.toInt()) {
            nextAllowed.take(cleanMax.toInt())
        } else {
            nextAllowed
        }
        selectedMode = nextMode
        allowedIds = trimmedAllowed
        maxViewers = cleanMax
        onSavePrivacySettings(nextMode, trimmedAllowed, cleanMax)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        EmeraldWaveHeader(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (showBlockedList) showBlockedList = false else onBack()
                        },
                        modifier = Modifier.testTag("privacy_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (showBlockedList) {
                                stringResource(R.string.blocked_contacts_title, blockedContacts.size)
                            } else {
                                stringResource(R.string.privacy_settings_title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (showBlockedList) {
                                "Tap Unblock to allow chats and calls again"
                            } else {
                                "Show Profile: ${selectedMode.label}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MintOnlineGlow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.16f),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MintOnlineGlow,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (showBlockedList) "${blockedContacts.size}" else selectedMode.shortBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        if (showBlockedList) {
            BlockedContactsContent(
                blockedContacts = blockedContacts,
                onUnblockContact = onUnblockContact,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Show Profile to Others (Yes / Selected People / No)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = EmeraldDark,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.show_profile_to_others),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Choose whether other users can see your profile photo, custom banner, and bio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        VisibilityOptionCard(
                            mode = ProfileVisibilityMode.EVERYONE,
                            selected = selectedMode == ProfileVisibilityMode.EVERYONE,
                            icon = Icons.Default.Public,
                            testTag = "privacy_mode_everyone",
                            onClick = { commitSettings(nextMode = ProfileVisibilityMode.EVERYONE) }
                        )
                        VisibilityOptionCard(
                            mode = ProfileVisibilityMode.SELECTED,
                            selected = selectedMode == ProfileVisibilityMode.SELECTED,
                            icon = Icons.Default.Group,
                            testTag = "privacy_mode_selected",
                            onClick = { commitSettings(nextMode = ProfileVisibilityMode.SELECTED) }
                        )
                        VisibilityOptionCard(
                            mode = ProfileVisibilityMode.NOBODY,
                            selected = selectedMode == ProfileVisibilityMode.NOBODY,
                            icon = Icons.Default.VisibilityOff,
                            testTag = "privacy_mode_nobody",
                            onClick = { commitSettings(nextMode = ProfileVisibilityMode.NOBODY) }
                        )
                    }
                }

                // 2. Custom Limit / Select Contacts
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("privacy_custom_limit_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = if (selectedMode == ProfileVisibilityMode.SELECTED) {
                        BorderStroke(1.5.dp, EmeraldMid.copy(alpha = 0.6f))
                    } else {
                        null
                    },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom Limit / Select Contacts",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (maxViewers > 0L) {
                                        "Limit: $maxViewers people • ${allowedIds.size}/$maxViewers selected"
                                    } else {
                                        "No limit • ${allowedIds.size} selected"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldDark,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("privacy_limit_summary")
                                )
                            }
                            FilterChip(
                                selected = maxViewers == 0L,
                                onClick = {
                                    val nextMax = if (maxViewers == 0L) {
                                        maxOf(1L, allowedIds.size.toLong())
                                    } else {
                                        0L
                                    }
                                    commitSettings(
                                        nextMode = ProfileVisibilityMode.SELECTED,
                                        nextMax = nextMax
                                    )
                                },
                                label = { Text(if (maxViewers == 0L) "No Limit" else "Limited") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MintLight,
                                    selectedLabelColor = EmeraldDark
                                ),
                                modifier = Modifier.testTag("privacy_limit_toggle_chip")
                            )
                        }

                        AnimatedVisibility(visible = selectedMode != ProfileVisibilityMode.SELECTED) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MintLight.copy(alpha = 0.75f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Changing the limit or selecting contacts will switch visibility to Selected People automatically.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldDeep,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // Custom People Limit Stepper
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Maximum Allowed People",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (maxViewers == 0L) "0 = Unlimited selected contacts" else "Up to $maxViewers contact(s) can view profile",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            val next = (maxViewers - 1L).coerceAtLeast(0L)
                                            commitSettings(
                                                nextMode = ProfileVisibilityMode.SELECTED,
                                                nextMax = next
                                            )
                                        },
                                        enabled = maxViewers > 0L,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("privacy_limit_decrease")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Remove,
                                            contentDescription = "Decrease people limit"
                                        )
                                    }
                                    Text(
                                        text = if (maxViewers == 0L) "∞" else maxViewers.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldDark,
                                        modifier = Modifier.testTag("privacy_limit_value")
                                    )
                                    IconButton(
                                        onClick = {
                                            val next = (maxViewers + 1L).coerceAtMost(500L)
                                            commitSettings(
                                                nextMode = ProfileVisibilityMode.SELECTED,
                                                nextMax = next
                                            )
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("privacy_limit_increase")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Increase people limit"
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        if (directContacts.isEmpty()) {
                            Text(
                                text = "No added contacts yet. Add contacts by @username to choose who can see your profile.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                directContacts.forEach { contact ->
                                    val key = contactViewerKey(contact)
                                    val checked = isContactAllowed(contact)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                if (checked) {
                                                    val updated = allowedIds.filterNot {
                                                        it == key || (contact.username.isNotBlank() && it.equals(contact.username, ignoreCase = true))
                                                    }
                                                    commitSettings(
                                                        nextMode = ProfileVisibilityMode.SELECTED,
                                                        nextAllowed = updated
                                                    )
                                                } else {
                                                    if (maxViewers > 0L && allowedIds.size >= maxViewers.toInt()) {
                                                        onShowNotice("People limit ($maxViewers) reached. Increase limit or uncheck another contact.")
                                                    } else if (key.isNotBlank()) {
                                                        val updated = (allowedIds + key).distinct()
                                                        commitSettings(
                                                            nextMode = ProfileVisibilityMode.SELECTED,
                                                            nextAllowed = updated
                                                        )
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                            .testTag("privacy_contact_row_${contact.id}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AvatarCircle(
                                            name = contact.name,
                                            photoData = contact.photoData,
                                            showOnlineDot = false,
                                            size = 42.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (contact.username.isNotBlank()) {
                                                Text(
                                                    text = "@${contact.username}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = EmeraldDark,
                                                checkmarkColor = Color.White
                                            ),
                                            modifier = Modifier.testTag("privacy_contact_checkbox_${contact.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Blocked Contacts Counter Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showBlockedList = true }
                        .testTag("privacy_blocked_contacts_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (blockedContacts.isNotEmpty()) BadgeBlockedBg else MintLight
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = null,
                                tint = if (blockedContacts.isNotEmpty()) ErrorRed else EmeraldDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.blocked_contacts_title, blockedContacts.size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("privacy_blocked_count_text")
                            )
                            Text(
                                text = if (blockedContacts.isEmpty()) {
                                    "You have not blocked any contacts"
                                } else {
                                    "View blocked users and unblock anytime"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (blockedContacts.isNotEmpty()) ErrorContainerLight else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = blockedContacts.size.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (blockedContacts.isNotEmpty()) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open blocked contacts",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun VisibilityOptionCard(
    mode: ProfileVisibilityMode,
    selected: Boolean,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MintLight.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (selected) BorderStroke(1.2.dp, EmeraldMid) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) EmeraldDark else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) EmeraldDeep else MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) EmeraldDark else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = mode.shortBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = EmeraldDark)
            )
        }
    }
}

@Composable
private fun BlockedContactsContent(
    blockedContacts: List<ChatThread>,
    onUnblockContact: (ChatThread) -> Unit,
    modifier: Modifier = Modifier
) {
    if (blockedContacts.isEmpty()) {
        Box(
            modifier = modifier.padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.testTag("blocked_contacts_empty_state")
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MintLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldBright,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Text(
                    text = "No Blocked Contacts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Blocked users appear here and can be unblocked at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.testTag("blocked_contacts_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(blockedContacts, key = { it.id }) { contact ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("blocked_contact_row_${contact.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AvatarCircle(
                            name = contact.name,
                            photoData = contact.photoData,
                            isGroup = contact.threadType == "group",
                            showOnlineDot = false,
                            size = 46.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (contact.username.isNotBlank()) {
                                Text(
                                    text = "@${contact.username}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BadgeBlockedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Button(
                            onClick = { onUnblockContact(contact) },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("unblock_contact_button_${contact.id}")
                        ) {
                            Text(
                                text = "Unblock",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

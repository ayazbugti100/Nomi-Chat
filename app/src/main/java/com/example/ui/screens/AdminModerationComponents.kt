package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ADMIN_GMAIL
import com.example.data.model.AdminUserRecord
import com.example.data.model.ModerationAlert
import com.example.ui.components.AvatarCircle
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class BannedEntryItem(
    val key: String,
    val uid: String,
    val username: String,
    val displayName: String,
    val photoData: String?,
    val email: String,
    val reason: String,
    val reportCount: Long,
    val userRecord: AdminUserRecord?,
    val alertRecord: ModerationAlert?
)

@Composable
fun AdminModerationDialog(
    alerts: List<ModerationAlert>,
    users: List<AdminUserRecord>,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onIgnore: (String) -> Unit,
    onBanAlert: (ModerationAlert) -> Unit,
    onRecoveryAlert: (ModerationAlert) -> Unit,
    onVerifyUser: (AdminUserRecord, Boolean) -> Unit,
    onBanUser: (AdminUserRecord) -> Unit,
    onUnbanUser: (AdminUserRecord) -> Unit
) {
    AdminModerationScreen(
        alerts = alerts,
        users = users,
        isLoading = isLoading,
        onDismiss = onDismiss,
        onIgnore = onIgnore,
        onBanAlert = onBanAlert,
        onRecoveryAlert = onRecoveryAlert,
        onVerifyUser = onVerifyUser,
        onBanUser = onBanUser,
        onUnbanUser = onUnbanUser
    )
}

@Composable
fun AdminModerationScreen(
    alerts: List<ModerationAlert>,
    users: List<AdminUserRecord>,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onIgnore: (String) -> Unit,
    onBanAlert: (ModerationAlert) -> Unit,
    onRecoveryAlert: (ModerationAlert) -> Unit,
    onVerifyUser: (AdminUserRecord, Boolean) -> Unit,
    onBanUser: (AdminUserRecord) -> Unit,
    onUnbanUser: (AdminUserRecord) -> Unit
) {
    BackHandler(onBack = onDismiss)

    val initialTab = remember {
        when {
            alerts.any { it.status == "pending" } -> 0
            users.any { it.isBanned } || alerts.any { it.status == "banned" } -> 1
            else -> 2
        }
    }
    var selectedTab by remember { mutableIntStateOf(initialTab) } // 0: Pending, 1: Banned, 2: Users, 3: All Alerts
    var searchQuery by remember { mutableStateOf("") }
    var userActionTargetUid by remember { mutableStateOf<String?>(null) }

    val usersByUid = remember(users) {
        users.filter { it.uid.isNotBlank() }.associateBy { it.uid }
    }
    val usersByUsername = remember(users) {
        users.filter { it.username.isNotBlank() }.associateBy { it.username.lowercase() }
    }

    val pendingAlerts = remember(alerts) { alerts.filter { it.status == "pending" } }

    val bannedEntries = remember(users, alerts, usersByUid, usersByUsername) {
        val alertByUid = alerts.filter { it.reportedUid.isNotBlank() }.groupBy { it.reportedUid }
        val alertByHandle = alerts.filter { it.reportedUsername.isNotBlank() }.groupBy { it.reportedUsername.lowercase() }

        val fromUsers = users.filter { it.isBanned }.map { user ->
            val matchingAlert = alertByUid[user.uid]?.firstOrNull { it.status == "banned" }
                ?: alertByHandle[user.username.lowercase()]?.firstOrNull { it.status == "banned" }
                ?: alertByUid[user.uid]?.firstOrNull()
                ?: alertByHandle[user.username.lowercase()]?.firstOrNull()
            BannedEntryItem(
                key = "user_${user.uid}",
                uid = user.uid,
                username = user.username,
                displayName = user.displayName.ifBlank { "@${user.username}" },
                photoData = user.photoData,
                email = user.email,
                reason = matchingAlert?.reason?.takeIf { it.isNotBlank() }
                    ?: if (user.bannedByAdmin) "Admin moderation ban" else "Report threshold reached",
                reportCount = user.reportCount,
                userRecord = user,
                alertRecord = matchingAlert
            )
        }

        val representedUids = fromUsers.mapNotNull { it.uid.takeIf(String::isNotBlank) }.toSet()
        val representedHandles = fromUsers.mapNotNull { it.username.lowercase().takeIf(String::isNotBlank) }.toSet()

        val fromAlertsOnly = alerts
            .filter { it.status == "banned" }
            .filter { alert ->
                val uidTaken = alert.reportedUid.isNotBlank() && alert.reportedUid in representedUids
                val handleTaken = alert.reportedUsername.isNotBlank() && alert.reportedUsername.lowercase() in representedHandles
                !uidTaken && !handleTaken
            }
            .distinctBy { it.reportedUid.ifBlank { it.reportedUsername.lowercase() } }
            .mapNotNull { alert ->
                val resolvedUser = usersByUid[alert.reportedUid]
                    ?: usersByUsername[alert.reportedUsername.lowercase()]
                // If user record exists and is already active (recovered), skip stale alert
                if (resolvedUser != null && !resolvedUser.isBanned) return@mapNotNull null
                BannedEntryItem(
                    key = "alert_${alert.id}",
                    uid = alert.reportedUid,
                    username = alert.reportedUsername,
                    displayName = alert.reportedDisplayName.ifBlank {
                        resolvedUser?.displayName.orEmpty().ifBlank { "@${alert.reportedUsername}" }
                    },
                    photoData = resolvedUser?.photoData,
                    email = resolvedUser?.email.orEmpty(),
                    reason = alert.reason.ifBlank { "Moderation ban" },
                    reportCount = resolvedUser?.reportCount ?: 0L,
                    userRecord = resolvedUser,
                    alertRecord = alert
                )
            }

        fromUsers + fromAlertsOnly
    }

    val query = searchQuery.trim()
    val filteredPendingAlerts = remember(pendingAlerts, query) {
        if (query.isBlank()) pendingAlerts
        else pendingAlerts.filter {
            it.reportedDisplayName.contains(query, ignoreCase = true) ||
                it.reportedUsername.contains(query, ignoreCase = true) ||
                it.reportedUid.contains(query, ignoreCase = true) ||
                it.reporterUsername.contains(query, ignoreCase = true) ||
                it.reason.contains(query, ignoreCase = true)
        }
    }
    val filteredBannedEntries = remember(bannedEntries, query) {
        if (query.isBlank()) bannedEntries
        else bannedEntries.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true) ||
                it.uid.contains(query, ignoreCase = true) ||
                it.email.contains(query, ignoreCase = true) ||
                it.reason.contains(query, ignoreCase = true)
        }
    }
    val filteredUsers = remember(users, query) {
        if (query.isBlank()) users
        else users.filter {
            it.username.contains(query, ignoreCase = true) ||
                it.displayName.contains(query, ignoreCase = true) ||
                it.email.contains(query, ignoreCase = true) ||
                it.uid.contains(query, ignoreCase = true)
        }
    }
    val filteredAllAlerts = remember(alerts, query) {
        if (query.isBlank()) alerts
        else alerts.filter {
            it.reportedDisplayName.contains(query, ignoreCase = true) ||
                it.reportedUsername.contains(query, ignoreCase = true) ||
                it.reportedUid.contains(query, ignoreCase = true) ||
                it.reporterUsername.contains(query, ignoreCase = true) ||
                it.reason.contains(query, ignoreCase = true)
        }
    }

    val userActionTarget = userActionTargetUid?.let { uid -> usersByUid[uid] }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("admin_moderation_dialog"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .navigationBarsPadding()
            ) {
                // Modern Full-Bleed Gradient Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(EmeraldDeep, EmeraldDark)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MintOnlineGlow,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Admin Moderation Alerts",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = MintOnlineGlow,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "Locked to $ADMIN_GMAIL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MintLight,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color.White.copy(alpha = 0.14f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(MintOnlineGlow)
                                    )
                                    Text(
                                        text = "Live Sync",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("admin_dialog_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close admin panel",
                                    tint = Color.White
                                )
                            }
                        }

                        // Summary Stat Strip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AdminStatTile(
                                label = "Pending",
                                count = pendingAlerts.size,
                                accentColor = Color(0xFFFFD54F),
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_stat_pending")
                            )
                            AdminStatTile(
                                label = "Banned",
                                count = bannedEntries.size,
                                accentColor = Color(0xFFFF8A80),
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_stat_banned")
                            )
                            AdminStatTile(
                                label = "Users",
                                count = users.size,
                                accentColor = MintOnlineGlow,
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_stat_users")
                            )
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        color = EmeraldBright,
                        trackColor = MintLight,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Filter Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("Pending (${pendingAlerts.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_pending")
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("Banned (${bannedEntries.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ErrorRed,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_banned")
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        label = { Text("Users (${users.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_users")
                    )
                    FilterChip(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        label = { Text("All Alerts (${alerts.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_all_alerts")
                    )
                }

                // Unified Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search name, @username, ID, or reason...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .testTag("admin_user_search_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                when {
                    selectedTab == 0 && filteredPendingAlerts.isEmpty() -> {
                        AdminEmptyState(
                            title = if (query.isBlank()) "All Clear" else "No Matching Alerts",
                            subtitle = if (query.isBlank()) {
                                "No pending moderation alerts right now."
                            } else {
                                "No pending alerts matched \"$query\"."
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    selectedTab == 1 && filteredBannedEntries.isEmpty() -> {
                        AdminEmptyState(
                            title = if (query.isBlank()) "No Banned Accounts" else "No Matching Banned Users",
                            subtitle = if (query.isBlank()) {
                                "Banned accounts appear here until you tap Recover."
                            } else {
                                "No banned accounts matched \"$query\"."
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    selectedTab == 2 && filteredUsers.isEmpty() -> {
                        AdminEmptyState(
                            title = "No Users Found",
                            subtitle = "No registered profiles matched your search.",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    selectedTab == 3 && filteredAllAlerts.isEmpty() -> {
                        AdminEmptyState(
                            title = "No Moderation History",
                            subtitle = if (query.isBlank()) {
                                "No moderation alert records yet."
                            } else {
                                "No alerts matched \"$query\"."
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (selectedTab) {
                                0 -> {
                                    items(filteredPendingAlerts, key = { it.id }) { alert ->
                                        val matchedUser = usersByUid[alert.reportedUid]
                                            ?: usersByUsername[alert.reportedUsername.lowercase()]
                                        ModerationAlertCard(
                                            alert = alert,
                                            matchedUser = matchedUser,
                                            isActionBusy = isLoading,
                                            onIgnore = { onIgnore(alert.id) },
                                            onBan = { onBanAlert(alert) },
                                            onRecovery = { onRecoveryAlert(alert) }
                                        )
                                    }
                                }
                                1 -> {
                                    items(filteredBannedEntries, key = { it.key }) { entry ->
                                        AdminBannedUserCard(
                                            entry = entry,
                                            isActionBusy = isLoading,
                                            onRecover = {
                                                when {
                                                    entry.userRecord != null -> onUnbanUser(entry.userRecord)
                                                    entry.alertRecord != null -> onRecoveryAlert(entry.alertRecord)
                                                }
                                            },
                                            onOpenManage = {
                                                entry.userRecord?.let { userActionTargetUid = it.uid }
                                            }
                                        )
                                    }
                                }
                                2 -> {
                                    items(filteredUsers, key = { it.uid }) { user ->
                                        AdminUserChatRow(
                                            user = user,
                                            onTap = { userActionTargetUid = user.uid }
                                        )
                                    }
                                }
                                else -> {
                                    items(filteredAllAlerts, key = { it.id }) { alert ->
                                        val matchedUser = usersByUid[alert.reportedUid]
                                            ?: usersByUsername[alert.reportedUsername.lowercase()]
                                        ModerationAlertCard(
                                            alert = alert,
                                            matchedUser = matchedUser,
                                            isActionBusy = isLoading,
                                            onIgnore = { onIgnore(alert.id) },
                                            onBan = { onBanAlert(alert) },
                                            onRecovery = { onRecoveryAlert(alert) }
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

    userActionTarget?.let { targetUser ->
        AdminUserActionBottomSheet(
            user = targetUser,
            isActionBusy = isLoading,
            onDismiss = { userActionTargetUid = null },
            onVerify = {
                onVerifyUser(targetUser, !targetUser.verified)
                userActionTargetUid = null
            },
            onBan = {
                onBanUser(targetUser)
                userActionTargetUid = null
            },
            onUnban = {
                onUnbanUser(targetUser)
                userActionTargetUid = null
            }
        )
    }
}

@Composable
private fun AdminStatTile(
    label: String,
    count: Int,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.11f),
        border = BorderStroke(
            width = if (selected) 1.2.dp else 0.8.dp,
            color = if (selected) accentColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AdminEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MintLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = EmeraldDark,
                    modifier = Modifier.size(30.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdminBannedUserCard(
    entry: BannedEntryItem,
    isActionBusy: Boolean,
    onRecover: () -> Unit,
    onOpenManage: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_banned_card_${entry.key}"),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF5F5),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AvatarCircle(
                    photoData = entry.photoData,
                    name = entry.displayName,
                    size = 50.dp,
                    borderColor = ErrorRed.copy(alpha = 0.65f)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${entry.username.ifBlank { "unknown" }}" +
                            if (entry.email.isNotBlank()) " • ${entry.email}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.uid.isNotBlank()) {
                        Text(
                            text = "ID: ${entry.uid}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = ErrorRed
                ) {
                    Text(
                        text = "BANNED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (entry.reportCount > 0L) {
                    Text(
                        text = "${entry.reportCount} reports",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entry.userRecord != null) {
                    OutlinedButton(
                        onClick = onOpenManage,
                        enabled = !isActionBusy,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("admin_banned_manage_${entry.key}")
                    ) {
                        Text("Actions", maxLines = 1)
                    }
                }
                Button(
                    onClick = onRecover,
                    enabled = !isActionBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                    modifier = Modifier
                        .weight(1.4f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_banned_recover_${entry.key}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Recover Account", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AdminUserChatRow(
    user: AdminUserRecord,
    onTap: () -> Unit
) {
    val statusBg = when {
        user.isBanned -> Color(0xFFFFEBEE)
        user.verified -> MintLight
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val statusFg = when {
        user.isBanned -> ErrorRed
        user.verified -> EmeraldDark
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        user.isBanned -> "Banned"
        user.verified -> "Verified"
        else -> "Active"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .testTag("admin_user_row_${user.uid}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(
            1.dp,
            if (user.isBanned) ErrorRed.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarCircle(
                photoData = user.photoData,
                name = user.displayName.ifBlank { user.username },
                showOnlineDot = user.online && !user.isBanned,
                size = 48.dp,
                borderColor = if (user.isBanned) ErrorRed.copy(alpha = 0.6f) else null
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = user.displayName.ifBlank { "@${user.username}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (user.verified) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified Blue Tick",
                            tint = Color(0xFF1DA1F2),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "@${user.username}" + if (user.email.isNotBlank()) " • ${user.email}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "ID: ${user.uid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusBg
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusFg,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AdminUserActionBottomSheet(
    user: AdminUserRecord,
    isActionBusy: Boolean,
    onDismiss: () -> Unit,
    onVerify: () -> Unit,
    onBan: () -> Unit,
    onUnban: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("admin_user_action_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AvatarCircle(
                        photoData = user.photoData,
                        name = user.displayName.ifBlank { user.username },
                        showOnlineDot = user.online && !user.isBanned,
                        size = 52.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = user.displayName.ifBlank { "@${user.username}" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (user.verified) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "Verified",
                                    tint = Color(0xFF1DA1F2),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = "@${user.username} • ID: ${user.uid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close action menu")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 1. Verify / Unverify Option
                Button(
                    onClick = onVerify,
                    enabled = !isActionBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (user.verified) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1DA1F2)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("admin_menu_verify_${user.uid}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = if (user.verified) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (user.verified) "Remove Blue Tick (Unverify)" else "Verify (Grant Blue Tick)",
                        color = if (user.verified) MaterialTheme.colorScheme.onSurface else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 2. Ban / Restore Option
                if (!user.isBanned) {
                    Button(
                        onClick = onBan,
                        enabled = !isActionBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_menu_ban_${user.uid}")
                    ) {
                        Icon(imageVector = Icons.Default.Block, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = "Ban Account", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onUnban,
                        enabled = !isActionBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldBright),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_menu_unban_${user.uid}")
                    ) {
                        Icon(imageVector = Icons.Default.Restore, contentDescription = null, tint = EmeraldDark)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = "Recover / Unban Account", color = EmeraldDark, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ModerationAlertCard(
    alert: ModerationAlert,
    matchedUser: AdminUserRecord?,
    isActionBusy: Boolean,
    onIgnore: () -> Unit,
    onBan: () -> Unit,
    onRecovery: () -> Unit
) {
    val timeText = remember(alert.createdAtMs) {
        if (alert.createdAtMs > 0L) {
            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(alert.createdAtMs))
        } else {
            "Just now"
        }
    }
    val effectiveStatus = when {
        matchedUser?.isBanned == true -> "banned"
        alert.status == "banned" && matchedUser != null && !matchedUser.isBanned -> "recovered"
        else -> alert.status
    }
    val (statusLabel, statusBg, statusFg) = when (effectiveStatus) {
        "banned" -> Triple("BANNED", Color(0xFFFFEBEE), ErrorRed)
        "recovered" -> Triple("RECOVERED", MintLight, EmeraldDark)
        "ignored" -> Triple("IGNORED", Color(0xFFECEFF1), Color(0xFF455A64))
        else -> Triple("PENDING", Color(0xFFFFF8E1), Color(0xFFB45309))
    }
    val displayName = matchedUser?.displayName?.takeIf { it.isNotBlank() }
        ?: alert.reportedDisplayName.ifBlank { "@${alert.reportedUsername}" }
    val resolvedUid = alert.reportedUid.ifBlank { matchedUser?.uid.orEmpty() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("moderation_alert_card_${alert.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            if (effectiveStatus == "banned") ErrorRed.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarCircle(
                    photoData = matchedUser?.photoData,
                    name = displayName,
                    showOnlineDot = matchedUser?.online == true && effectiveStatus != "banned",
                    size = 50.dp,
                    borderColor = if (effectiveStatus == "banned") ErrorRed.copy(alpha = 0.6f) else null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (matchedUser?.verified == true) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Verified Blue Tick",
                                tint = Color(0xFF1DA1F2),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = "@${alert.reportedUsername.ifBlank { matchedUser?.username ?: "user" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (resolvedUid.isNotBlank()) {
                        Text(
                            text = "ID: $resolvedUid",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusBg
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ErrorRed.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = "Reason: ${alert.reason.ifBlank { "Other" }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ErrorRed,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Reported by @${alert.reporterUsername.ifBlank { "user" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onIgnore,
                    enabled = !isActionBusy && effectiveStatus != "ignored",
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_action_ignore_${alert.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Ignore", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onBan,
                    enabled = !isActionBusy && effectiveStatus != "banned",
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_action_ban_${alert.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Ban", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onRecovery,
                    enabled = !isActionBusy && effectiveStatus != "recovered",
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_action_recovery_${alert.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Recover", maxLines = 1, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun InAppAdminAlertBanner(
    alert: ModerationAlert,
    reportedPhotoData: String? = null,
    onOpenAdminPanel: () -> Unit,
    onIgnore: () -> Unit,
    onBan: () -> Unit,
    onRecovery: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenAdminPanel)
            .testTag("in_app_admin_alert_banner"),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF14211D),
        border = BorderStroke(1.dp, MintOnlineGlow.copy(alpha = 0.4f)),
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AvatarCircle(
                    photoData = reportedPhotoData,
                    name = alert.reportedDisplayName.ifBlank { alert.reportedUsername },
                    size = 40.dp,
                    borderColor = Color(0xFFFF8A80)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Admin Alert: ${alert.reportedDisplayName.ifBlank { "@${alert.reportedUsername}" }} (@${alert.reportedUsername})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Reason: ${alert.reason} • By @${alert.reporterUsername}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MintLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("dismiss_admin_banner")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss admin alert",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onIgnore,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("banner_admin_ignore")
                ) {
                    Text("Ignore", color = Color(0xFFCFD8DC), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onBan,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("banner_admin_ban")
                ) {
                    Text("Ban", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                Button(
                    onClick = onRecovery,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldBright),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("banner_admin_recovery")
                ) {
                    Text("Recover", style = MaterialTheme.typography.labelMedium, color = EmeraldDark, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

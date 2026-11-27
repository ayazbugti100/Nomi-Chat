package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var selectedTab by remember { mutableStateOf(0) } // 0: Users, 1: Pending Alerts, 2: All Alerts
    var searchQuery by remember { mutableStateOf("") }
    var userActionTarget by remember { mutableStateOf<AdminUserRecord?>(null) }

    val pendingAlerts = remember(alerts) { alerts.filter { it.status == "pending" } }
    val filteredUsers = remember(users, searchQuery) {
        if (searchQuery.isBlank()) users
        else users.filter {
            it.username.contains(searchQuery, ignoreCase = true) ||
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.email.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler(onBack = onDismiss)
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("admin_moderation_dialog"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(EmeraldDeep)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(EmeraldDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MintOnlineGlow,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Admin Control Panel",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                }

                // Tabs / Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("Users (${users.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_users")
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("Pending (${pendingAlerts.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_pending")
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        label = { Text("All Alerts (${alerts.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_tab_all_alerts")
                    )
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search users by name, handle, or email...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("admin_user_search_input"),
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = EmeraldDark)
                    }
                } else if (selectedTab == 0 && filteredUsers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No users found matching your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (selectedTab == 1 && pendingAlerts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No pending moderation alerts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (selectedTab == 2 && alerts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No moderation alert records yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectedTab == 0) {
                            items(filteredUsers, key = { it.uid }) { user ->
                                AdminUserChatRow(
                                    user = user,
                                    onTap = { userActionTarget = user }
                                )
                            }
                        } else if (selectedTab == 1) {
                            items(pendingAlerts, key = { it.id }) { alert ->
                                ModerationAlertCard(
                                    alert = alert,
                                    onIgnore = { onIgnore(alert.id) },
                                    onBan = { onBanAlert(alert) },
                                    onRecovery = { onRecoveryAlert(alert) }
                                )
                            }
                        } else {
                            items(alerts, key = { it.id }) { alert ->
                                ModerationAlertCard(
                                    alert = alert,
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

        userActionTarget?.let { targetUser ->
            AdminUserActionBottomSheet(
                user = targetUser,
                onDismiss = { userActionTarget = null },
                onVerify = {
                    onVerifyUser(targetUser, !targetUser.verified)
                    userActionTarget = null
                },
                onBan = {
                    onBanUser(targetUser)
                    userActionTarget = null
                },
                onUnban = {
                    onUnbanUser(targetUser)
                    userActionTarget = null
                }
            )
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(EmeraldDark),
                contentAlignment = Alignment.Center
            ) {
                AvatarCircle(
                    photoData = user.photoData,
                    name = user.displayName.ifBlank { user.username },
                    size = 46.dp
                )
                if (user.online) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(MintOnlineGlow)
                    )
                }
            }

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
                        overflow = TextOverflow.Ellipsis
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
                if (user.bio.isNotBlank()) {
                    Text(
                        text = user.bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Moderate @${user.username}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = user.displayName + if (user.verified) " (Verified)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

                // 2. Ban Option (if not already banned)
                if (!user.isBanned) {
                    Button(
                        onClick = onBan,
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_menu_ban_${user.uid}")
                    ) {
                        Icon(imageVector = Icons.Default.Block, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = "Ban Account Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // 3. Restore / Unban Option (if banned)
                    Button(
                        onClick = onUnban,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldBright),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_menu_unban_${user.uid}")
                    ) {
                        Icon(imageVector = Icons.Default.Restore, contentDescription = null, tint = EmeraldDark)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = "Restore / Unban Account", color = EmeraldDark, fontWeight = FontWeight.Bold)
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
    val (statusLabel, statusBg, statusFg) = when (alert.status) {
        "banned" -> Triple("BANNED", Color(0xFFFFEBEE), ErrorRed)
        "recovered" -> Triple("RECOVERED", MintLight, EmeraldDark)
        "ignored" -> Triple("IGNORED", Color(0xFFECEFF1), Color(0xFF455A64))
        else -> Triple("PENDING", Color(0xFFFFF8E1), Color(0xFFB45309))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("moderation_alert_card_${alert.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alert.reportedDisplayName.ifBlank { "@${alert.reportedUsername}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${alert.reportedUsername}" + if (alert.reportedUid.isNotBlank()) " • ID: ${alert.reportedUid.take(10)}..." else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    enabled = alert.status != "ignored",
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
                    enabled = alert.status != "banned",
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
                    enabled = alert.status != "recovered",
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldBright),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("admin_action_recovery_${alert.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Recovery", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun InAppAdminAlertBanner(
    alert: ModerationAlert,
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ErrorRed.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Admin moderation alert",
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Admin Alert: @${alert.reportedUsername}",
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
                    Text("Recovery", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
    }
}

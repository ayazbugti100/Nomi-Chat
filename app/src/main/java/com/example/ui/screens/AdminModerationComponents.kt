package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.data.model.ModerationAlert
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
    onDismiss: () -> Unit,
    onIgnore: (String) -> Unit,
    onBan: (ModerationAlert) -> Unit,
    onRecovery: (ModerationAlert) -> Unit
) {
    var showPendingOnly by remember { mutableStateOf(true) }
    val pendingAlerts = remember(alerts) { alerts.filter { it.status == "pending" } }
    val visibleAlerts = if (showPendingOnly) pendingAlerts else alerts

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = 680.dp)
                .testTag("admin_moderation_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(EmeraldDeep)
                        .padding(horizontal = 18.dp, vertical = 14.dp)
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
                                text = "Admin Moderation Alerts",
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
                                contentDescription = "Close admin alerts",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Filter Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = showPendingOnly,
                        onClick = { showPendingOnly = true },
                        label = { Text("Pending (${pendingAlerts.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_filter_pending")
                    )
                    FilterChip(
                        selected = !showPendingOnly,
                        onClick = { showPendingOnly = false },
                        label = { Text("All History (${alerts.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldDark,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("admin_filter_all")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (visibleAlerts.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp)
                            .testTag("admin_alerts_empty"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MintLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = EmeraldDark,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = if (showPendingOnly) "No pending moderation alerts" else "No moderation records yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "New user safety reports and moderation events appear here in real time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(visibleAlerts, key = { it.id.ifBlank { "${it.reportedUsername}_${it.createdAtMs}" } }) { alert ->
                            ModerationAlertCard(
                                alert = alert,
                                onIgnore = { onIgnore(alert.id) },
                                onBan = { onBan(alert) },
                                onRecovery = { onRecovery(alert) }
                            )
                        }
                    }
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

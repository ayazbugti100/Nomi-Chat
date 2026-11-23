package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.network.NetworkStatus
import com.example.ui.components.AvatarCircle
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.EmeraldVibrant
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow

@Composable
fun ProfileScreen(
    profile: UserProfile?,
    themeMode: ThemeMode,
    notificationsEnabled: Boolean,
    isAppLockEnabled: Boolean,
    onBackToChats: () -> Unit,
    onEditNameClick: () -> Unit,
    onEditBioClick: () -> Unit,
    onEditUsernameClick: () -> Unit,
    onEditDobClick: () -> Unit,
    onPhotoPicked: (android.net.Uri) -> Unit,
    onOpenPasscodeSetup: () -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onOpenAppearancePicker: () -> Unit,
    onOpenAddContact: () -> Unit,
    onLogoutClick: () -> Unit,
    networkStatus: NetworkStatus = NetworkStatus(),
    isCloudSession: Boolean = false,
    onReconnectSyncClick: () -> Unit = {},
    onConnectGoogleSyncClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackToChats)

    val context = LocalContext.current
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                onPhotoPicked(uri)
            }
        }
    )

    val displayName = profile?.displayName?.ifBlank { "Nomi User" } ?: "Nomi User"
    val bio = profile?.bio?.ifBlank { "Dream Big... 💚" } ?: "Dream Big... 💚"
    val username = profile?.username?.ifBlank { "nomiuser" } ?: "nomiuser"

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
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackToChats,
                        modifier = Modifier.testTag("profile_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to chats",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = stringResource(R.string.profile_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    IconButton(
                        onClick = onOpenPasscodeSetup,
                        modifier = Modifier.testTag("profile_security_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Security and passcode",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Circular Profile Avatar with Camera Overlay Button
                Box(modifier = Modifier.size(108.dp)) {
                    AvatarCircle(
                        name = displayName,
                        photoData = profile?.photoData,
                        size = 106.dp,
                        borderColor = Color.White.copy(alpha = 0.9f)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .shadow(4.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, EmeraldLightBorder, CircleShape)
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .testTag("change_photo_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Change profile photo",
                            tint = EmeraldDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    if (profile?.verified != false) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(EmeraldVibrant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Verified",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (networkStatus.isConnected) EmeraldVibrant else Color(0xFFFFB74D))
                    )
                    Text(
                        text = if (networkStatus.isConnected) {
                            "${stringResource(R.string.status_online)} • ${networkStatus.transportLabel}"
                        } else {
                            "Offline • Queued locally"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MintOnlineGlow
                    )
                }
            }
        }

        // Settings Cards overlapping wave bottom
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-12).dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 110.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsGroupCard {
                    SettingItemRow(
                        icon = Icons.Default.Person,
                        title = stringResource(R.string.edit_profile),
                        subtitle = stringResource(R.string.edit_profile_desc),
                        testTag = "edit_profile_row",
                        onClick = onEditNameClick
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    SettingItemRow(
                        icon = Icons.Default.Edit,
                        title = stringResource(R.string.bio_title),
                        subtitle = bio,
                        testTag = "edit_bio_row",
                        onClick = onEditBioClick
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    SettingItemRow(
                        icon = Icons.Default.AlternateEmail,
                        title = stringResource(R.string.username_title),
                        subtitle = "@$username • ${profile?.provider?.replaceFirstChar { it.uppercase() } ?: "Google"}",
                        testTag = "edit_username_row",
                        onClick = onEditUsernameClick
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    SettingItemRow(
                        icon = Icons.Default.Cake,
                        title = "Date of Birth",
                        subtitle = profile?.dob?.ifBlank { "2000-01-01" } ?: "2000-01-01",
                        testTag = "edit_dob_row",
                        onClick = onEditDobClick
                    )
                }
            }

            item {
                SettingsGroupCard {
                    SettingItemRow(
                        icon = Icons.Default.VerifiedUser,
                        title = stringResource(R.string.account_security),
                        subtitle = buildString {
                            append(if (isAppLockEnabled) "App Lock PIN on" else "Passcode off")
                            val statusLabel = if (profile?.accountStatus == "banned") "Banned" else "Active"
                            append(" • Status: $statusLabel")
                            val reports = profile?.reportCount ?: 0L
                            if (reports > 0L) {
                                append(" ($reports/10 reports)")
                            }
                        },
                        testTag = "account_security_row",
                        onClick = onOpenPasscodeSetup
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleNotifications(!notificationsEnabled) }
                            .padding(horizontal = 4.dp, vertical = 10.dp)
                            .testTag("notifications_toggle_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingIconBox(icon = Icons.Default.Notifications)
                            Column {
                                Text(
                                    text = stringResource(R.string.notifications_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.notifications_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = onToggleNotifications,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = EmeraldMid
                            )
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    SettingItemRow(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.appearance_title),
                        subtitle = "${themeMode.label} • Light, Dark or Auto",
                        testTag = "appearance_row",
                        onClick = onOpenAppearancePicker
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    SettingItemRow(
                        icon = Icons.Default.Contacts,
                        title = stringResource(R.string.add_contact_title),
                        subtitle = stringResource(R.string.add_contact_desc),
                        testTag = "profile_add_contact_row",
                        onClick = onOpenAddContact
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    SettingItemRow(
                        icon = if (isCloudSession && networkStatus.isConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        title = if (isCloudSession) "Cloud Sync (${networkStatus.transportLabel})" else "Cloud Sync (Local Mode)",
                        subtitle = when {
                            isCloudSession && networkStatus.isConnected -> "Real-time Firestore active • Tap to refresh"
                            isCloudSession -> "Offline • Auto-syncs when internet returns"
                            else -> "Tap to sign in with Google for multi-phone sync"
                        },
                        testTag = "cloud_sync_row",
                        onClick = {
                            if (isCloudSession) {
                                onReconnectSyncClick()
                            } else {
                                onConnectGoogleSyncClick()
                            }
                        }
                    )
                }
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onLogoutClick)
                        .testTag("logout_row"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 15.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Log out",
                            tint = ErrorRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.log_out),
                            style = MaterialTheme.typography.labelLarge,
                            color = ErrorRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private val EmeraldLightBorder = Color(0xFFD5EBE0)

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingIconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MintLight),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmeraldDark,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingIconBox(icon = icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

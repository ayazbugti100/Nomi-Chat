package com.example.ui.screens

import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.ADMIN_GMAIL
import com.example.data.model.ProfileVisibilityMode
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.network.NetworkStatus
import com.example.ui.components.AvatarCircle
import com.example.ui.components.computeImageCacheKey
import com.example.ui.components.resolveImageModel
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.EmeraldVibrant
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MintLight

private val VerifiedBadgeBlue = Color(0xFF1E9BF0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: UserProfile?,
    themeMode: ThemeMode,
    notificationsEnabled: Boolean,
    isAppLockEnabled: Boolean,
    onBackToChats: () -> Unit,
    onEditProfileClick: () -> Unit = {},
    onEditNameClick: () -> Unit,
    onEditBioClick: () -> Unit,
    onEditUsernameClick: () -> Unit,
    onEditDobClick: () -> Unit,
    onPhotoPicked: (android.net.Uri) -> Unit,
    onBannerPicked: (android.net.Uri) -> Unit = {},
    onOpenPrivacySettings: () -> Unit = {},
    onOpenPasscodeSetup: () -> Unit,
    onOpenChangePassword: () -> Unit = {},
    onToggleNotifications: (Boolean) -> Unit,
    onToggleBlackTheme: (Boolean) -> Unit = {},
    onOpenAppearancePicker: () -> Unit,
    onOpenAddContact: () -> Unit,
    onLogoutClick: () -> Unit,
    networkStatus: NetworkStatus = NetworkStatus(),
    isCloudSession: Boolean = false,
    onReconnectSyncClick: () -> Unit = {},
    onConnectGoogleSyncClick: () -> Unit = {},
    isUploadingBanner: Boolean = false,
    isUploadingPhoto: Boolean = false,
    isSavingProfile: Boolean = false,
    isChangingPassword: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    isAdminSession: Boolean = false,
    pendingModerationCount: Int = 0,
    onOpenAdminModeration: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    BackHandler {
        if (isSearchVisible) {
            isSearchVisible = false
            searchQuery = ""
        } else {
            onBackToChats()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                onPhotoPicked(uri)
            }
        }
    )
    val bannerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                onBannerPicked(uri)
            }
        }
    )

    val launchPhotoPicker = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    val launchBannerPicker = {
        bannerPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val rawDisplayName = profile?.displayName?.trim().orEmpty()
    val displayName = rawDisplayName.ifBlank { stringResource(R.string.set_name) }
    val rawUsername = profile?.username?.trim().orEmpty()
    val bio = profile?.bio?.trim()?.ifBlank { "Dream Big... 💚" } ?: "Dream Big... 💚"
    val dob = profile?.dob?.trim()?.ifBlank { "2000-01-01" } ?: "2000-01-01"
    val showOnlineStatus = profile?.showOnlineStatus ?: true
    val visibilityMode = ProfileVisibilityMode.fromKey(profile?.profileVisibility)
    val hasCustomBanner = !profile?.bannerData.isNullOrBlank()
    val query = searchQuery.trim().lowercase()

    fun matchesQuery(vararg terms: String): Boolean {
        if (query.isEmpty()) return true
        return terms.any { it.lowercase().contains(query) }
    }

    val isBlackThemeEnabled = themeMode == ThemeMode.DARK || (themeMode == ThemeMode.AUTO && isSystemInDarkTheme())
    val showProfileCard = matchesQuery("edit profile", "name", displayName, "bio", bio, "username", rawUsername, "date of birth", dob)
    val showSecurityCard = matchesQuery(
        "privacy", "online status", "app lock", "passcode", "pin",
        "change password", "password", "notifications", "black theme", "dark", "white", "appearance", "theme",
        "add contact", "cloud sync"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Clean Top App Bar matching screenshot
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackToChats,
                        modifier = Modifier.testTag("profile_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to chats",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.profile_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                isSearchVisible = !isSearchVisible
                                if (!isSearchVisible) searchQuery = ""
                            },
                            modifier = Modifier.testTag("profile_search_button")
                        ) {
                            Icon(
                                imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search settings",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { moreMenuExpanded = true },
                                modifier = Modifier.testTag("profile_security_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Profile options",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_profile)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = EmeraldDark)
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onEditProfileClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.privacy_settings_title)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = EmeraldDark)
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onOpenPrivacySettings()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.app_lock_title)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldDark)
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onOpenPasscodeSetup()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.change_password_title)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Key, contentDescription = null, tint = EmeraldDark)
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onOpenChangePassword()
                                    }
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = isSearchVisible) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_settings_hint)) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("profile_search_input")
                    )
                }

                if (isSavingProfile || isChangingPassword) {
                    LinearProgressIndicator(
                        color = EmeraldDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("profile_lazy_column"),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 112.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "profile_banner_and_identity") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top rectangular rounded Banner (tap anywhere on banner to pick photo)
                        ProfileBannerCard(
                            bannerData = profile?.bannerData,
                            hasCustomBanner = hasCustomBanner,
                            isUploading = isUploadingBanner,
                            onClick = launchBannerPicker
                        )

                        // Circular Profile Avatar + Set Name / Verified / @Username / Online status
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .testTag("profile_avatar_container")
                            ) {
                                AvatarCircle(
                                    name = displayName,
                                    photoData = profile?.photoData,
                                    size = 94.dp,
                                    borderColor = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable(enabled = !isUploadingPhoto, onClick = launchPhotoPicker)
                                        .testTag("profile_avatar_button")
                                )
                                if (isUploadingPhoto) {
                                    Box(
                                        modifier = Modifier
                                            .size(94.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("profile_photo_loading")
                                        )
                                    }
                                }
                                // Green circular camera badge matching reference screenshot
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(32.dp)
                                        .shadow(4.dp, CircleShape)
                                        .clip(CircleShape)
                                        .background(EmeraldBright)
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                        .clickable(enabled = !isUploadingPhoto, onClick = launchPhotoPicker)
                                        .testTag("change_photo_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Change profile photo",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onEditNameClick)
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .testTag("profile_display_name_text")
                                    )
                                    if (profile?.verified == true) {
                                        Icon(
                                            imageVector = Icons.Default.Verified,
                                            contentDescription = "Verified account",
                                            tint = VerifiedBadgeBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = if (rawUsername.isNotBlank()) "@$rawUsername" else stringResource(R.string.add_username),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(onClick = onEditUsernameClick)
                                        .padding(vertical = 2.dp)
                                        .testTag("profile_header_username")
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val dotColor = when {
                                        !showOnlineStatus -> Color(0xFFFFB74D)
                                        networkStatus.isConnected -> EmeraldBright
                                        else -> Color(0xFFFFB74D)
                                    }
                                    val statusLabel = when {
                                        !showOnlineStatus -> stringResource(R.string.status_hidden)
                                        networkStatus.isConnected -> stringResource(R.string.status_online)
                                        else -> "Offline • Queued locally"
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!showOnlineStatus || !networkStatus.isConnected) {
                                            Color(0xFFD97706)
                                        } else {
                                            EmeraldBright
                                        },
                                        modifier = Modifier.testTag("profile_online_status_text")
                                    )
                                }
                            }
                        }
                    }
                }

                if (showProfileCard) {
                    item(key = "profile_info_card") {
                        SettingsGroupCard(modifier = Modifier.widthIn(max = 600.dp)) {
                            if (matchesQuery("edit profile", "name", "photo", "bio")) {
                                SettingItemRow(
                                    icon = Icons.Default.Person,
                                    title = stringResource(R.string.edit_profile),
                                    subtitle = stringResource(R.string.edit_profile_desc),
                                    testTag = "edit_profile_row",
                                    onClick = onEditProfileClick
                                )
                            }
                            if (matchesQuery("bio", bio)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                SettingItemRow(
                                    icon = Icons.Default.Edit,
                                    title = stringResource(R.string.bio_title),
                                    subtitle = bio,
                                    testTag = "edit_bio_row",
                                    onClick = onEditBioClick
                                )
                            }
                            if (matchesQuery("username", rawUsername)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                SettingItemRow(
                                    icon = Icons.Default.AlternateEmail,
                                    title = stringResource(R.string.username_title),
                                    subtitle = if (rawUsername.isNotBlank()) "@$rawUsername" else "Set Username",
                                    testTag = "edit_username_row",
                                    onClick = onEditUsernameClick
                                )
                            }
                            if (matchesQuery("date of birth", "dob", dob)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                SettingItemRow(
                                    icon = Icons.Default.Cake,
                                    title = stringResource(R.string.dob_title),
                                    subtitle = dob,
                                    testTag = "edit_dob_row",
                                    onClick = onEditDobClick
                                )
                            }
                        }
                    }
                }

                if (showSecurityCard) {
                    item(key = "profile_security_card") {
                        SettingsGroupCard(modifier = Modifier.widthIn(max = 600.dp)) {
                            var needsDivider = false
                            if (matchesQuery("privacy", "online status", "blocked")) {
                                needsDivider = true
                                SettingItemRow(
                                    icon = Icons.Default.Shield,
                                    title = stringResource(R.string.privacy_settings_title),
                                    subtitle = buildString {
                                        append(if (showOnlineStatus) "Online visible" else "Online hidden")
                                        append(" • Profile: ${visibilityMode.label}")
                                    },
                                    testTag = "privacy_settings_row",
                                    onClick = onOpenPrivacySettings
                                )
                            }
                            if (matchesQuery("app lock", "passcode", "pin", "security")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                needsDivider = true
                                SettingItemRow(
                                    icon = Icons.Default.Lock,
                                    title = stringResource(R.string.app_lock_title),
                                    subtitle = if (isAppLockEnabled) {
                                        "Passcode PIN enabled"
                                    } else {
                                        stringResource(R.string.app_lock_desc)
                                    },
                                    testTag = "account_security_row",
                                    onClick = onOpenPasscodeSetup
                                )
                            }
                            if (matchesQuery("change password", "password")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                needsDivider = true
                                SettingItemRow(
                                    icon = Icons.Default.Key,
                                    title = stringResource(R.string.change_password_title),
                                    subtitle = stringResource(R.string.change_password_desc),
                                    testTag = "change_password_row",
                                    onClick = onOpenChangePassword
                                )
                            }
                            if (matchesQuery("notifications", "alerts")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                needsDivider = true
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
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
                                                fontWeight = FontWeight.SemiBold,
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
                            }
                            if (matchesQuery("black theme", "dark", "white", "theme")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                needsDivider = true
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .clickable { onToggleBlackTheme(!isBlackThemeEnabled) }
                                        .padding(horizontal = 4.dp, vertical = 10.dp)
                                        .testTag("black_theme_switch_row"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        SettingIconBox(icon = Icons.Default.DarkMode)
                                        Column {
                                            Text(
                                                text = stringResource(R.string.black_theme_title),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.black_theme_desc),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = isBlackThemeEnabled,
                                        onCheckedChange = onToggleBlackTheme,
                                        modifier = Modifier.testTag("black_theme_switch"),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = EmeraldMid
                                        )
                                    )
                                }
                            }
                            if (matchesQuery("appearance", "theme", "dark", "light")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                needsDivider = true
                                SettingItemRow(
                                    icon = Icons.Default.Palette,
                                    title = stringResource(R.string.appearance_title),
                                    subtitle = "${themeMode.label} • Light, Dark or Auto",
                                    testTag = "appearance_row",
                                    onClick = onOpenAppearancePicker
                                )
                            }
                            if (matchesQuery("add contact", "friend", "username")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                needsDivider = true
                                SettingItemRow(
                                    icon = Icons.Default.Contacts,
                                    title = stringResource(R.string.add_contact_title),
                                    subtitle = stringResource(R.string.add_contact_desc),
                                    testTag = "profile_add_contact_row",
                                    onClick = onOpenAddContact
                                )
                            }
                            if (matchesQuery("cloud sync", "sync", "backup")) {
                                if (needsDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                SettingItemRow(
                                    icon = if (isCloudSession && networkStatus.isConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                    title = if (isCloudSession) "Cloud Sync (${networkStatus.transportLabel})" else "Cloud Sync (Local Mode)",
                                    subtitle = when {
                                        isCloudSession && networkStatus.isConnected -> "Real-time sync active • Pull or tap to refresh"
                                        isCloudSession -> "Offline • Auto-syncs when internet returns"
                                        else -> "Tap to sign in with Google for multi-phone sync"
                                    },
                                    testTag = "cloud_sync_row",
                                    onClick = {
                                        if (isCloudSession) onReconnectSyncClick() else onConnectGoogleSyncClick()
                                    }
                                )
                            }
                        }
                    }
                }

                if (isAdminSession && matchesQuery("admin", "moderation", "alerts", "ban", "recovery")) {
                    item(key = "profile_admin_moderation_card") {
                        SettingsGroupCard(modifier = Modifier.widthIn(max = 600.dp)) {
                            SettingItemRow(
                                icon = Icons.Default.Shield,
                                title = if (pendingModerationCount > 0) {
                                    "Admin Moderation Alerts ($pendingModerationCount pending)"
                                } else {
                                    "Admin Moderation Alerts"
                                },
                                subtitle = "Verified Gmail lock: $ADMIN_GMAIL",
                                testTag = "admin_moderation_profile_row",
                                onClick = onOpenAdminModeration
                            )
                        }
                    }
                }

                item(key = "profile_logout_card") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onLogoutClick)
                            .testTag("logout_row"),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
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
}

@Composable
private fun ProfileBannerCard(
    bannerData: String?,
    hasCustomBanner: Boolean,
    isUploading: Boolean,
    onClick: () -> Unit
) {
    val bannerModel = remember(bannerData) { resolveImageModel(bannerData) }
    val wavePath = remember { Path() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = !isUploading, onClick = onClick)
            .testTag("profile_banner_card"),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 3.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(EmeraldDeep, EmeraldMid, EmeraldBright)
                    )
                )
        ) {
            if (bannerModel != null) {
                val bannerContext = LocalContext.current
                val bannerCacheKey = remember(bannerData) {
                    computeImageCacheKey("profile_banner", bannerData)
                }
                val bannerRequest = remember(bannerCacheKey, bannerModel) {
                    ImageRequest.Builder(bannerContext)
                        .data(bannerModel)
                        .memoryCacheKey(bannerCacheKey)
                        .diskCacheKey(bannerCacheKey)
                        .diskCachePolicy(if (bannerModel is ByteArray) CachePolicy.DISABLED else CachePolicy.ENABLED)
                        .crossfade(false)
                        .build()
                }
                key(bannerCacheKey) {
                    AsyncImage(
                        model = bannerRequest,
                        contentDescription = "Profile banner image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.28f)
                                )
                            )
                        )
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    drawCircle(
                        color = Color.White.copy(alpha = 0.14f),
                        radius = h * 0.65f,
                        center = Offset(w * 0.86f, h * 0.25f)
                    )
                    wavePath.reset()
                    wavePath.apply {
                        moveTo(0f, h * 0.68f)
                        cubicTo(w * 0.30f, h * 0.32f, w * 0.65f, h * 0.95f, w, h * 0.45f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(wavePath, color = Color.White.copy(alpha = 0.12f))
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .testTag("add_banner_button"),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.48f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = stringResource(
                            if (hasCustomBanner) R.string.change_banner else R.string.add_banner
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(
                            if (hasCustomBanner) R.string.change_banner else R.string.add_banner
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (isUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("profile_banner_loading")
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) EmeraldDark.copy(alpha = 0.35f) else MintLight),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDark) EmeraldBright else EmeraldDark,
            modifier = Modifier.size(21.dp)
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
            .minimumInteractiveComponentSize()
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
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

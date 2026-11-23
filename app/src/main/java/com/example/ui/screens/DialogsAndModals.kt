package com.example.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.BAN_REPORT_LIMIT
import com.example.data.model.ChatThread
import com.example.data.model.StoryItem
import com.example.data.model.ThemeMode
import com.example.ui.components.AvatarCircle
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.ErrorContainerLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.GoldCrown
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow
import com.example.ui.viewmodel.UsernameSearchState
import kotlin.math.max

@Composable
fun EditSingleFieldDialog(
    title: String,
    label: String,
    initialValue: String,
    maxLength: Int,
    sanitizeUsername: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { raw ->
                    val cleaned = if (sanitizeUsername) {
                        raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }
                    } else raw
                    if (cleaned.length <= maxLength) value = cleaned
                },
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_field_input")
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(value) },
                enabled = value.trim().length >= if (sanitizeUsername) 2 else 1,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                modifier = Modifier.testTag("edit_field_save_button")
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddContactDialog(
    usernameSearchState: UsernameSearchState = UsernameSearchState.Idle,
    onSearchUsername: (String) -> Unit = {},
    onResetSearch: () -> Unit = {},
    onDismiss: () -> Unit,
    onAddDirectChat: (username: String) -> Unit,
    onAddGroup: (name: String, username: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var isGroup by remember { mutableStateOf(false) }

    val cleanUsername = username.trim().removePrefix("@")

    AlertDialog(
        onDismissRequest = {
            onResetSearch()
            onDismiss()
        },
        title = {
            Text(
                text = if (isGroup) "Create New Group" else "Add Chat by Username",
                style = MaterialTheme.typography.titleMedium,
                color = EmeraldDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isGroup,
                        onClick = { isGroup = false },
                        label = { Text("Direct Chat") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MintLight,
                            selectedLabelColor = EmeraldDark
                        )
                    )
                    FilterChip(
                        selected = isGroup,
                        onClick = {
                            isGroup = true
                            onResetSearch()
                        },
                        label = { Text("Group") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MintLight,
                            selectedLabelColor = EmeraldDark
                        )
                    )
                }

                if (!isGroup) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { raw ->
                                if (raw.length <= 42) {
                                    username = raw.replace("\n", "")
                                    if (usernameSearchState != UsernameSearchState.Idle) {
                                        onResetSearch()
                                    }
                                }
                            },
                            label = { Text("Username") },
                            placeholder = { Text("Enter exact @username") },
                            singleLine = true,
                            isError = usernameSearchState is UsernameSearchState.NotFound,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_contact_username_input")
                        )

                        Button(
                            onClick = { onSearchUsername(username) },
                            enabled = cleanUsername.isNotEmpty() && usernameSearchState !is UsernameSearchState.Loading,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldMid),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("find_username_button")
                        ) {
                            if (usernameSearchState is UsernameSearchState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text("Find", color = Color.White)
                            }
                        }
                    }

                    when (usernameSearchState) {
                        is UsernameSearchState.Found -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("found_user_card"),
                                shape = RoundedCornerShape(12.dp),
                                color = MintLight,
                                border = BorderStroke(1.dp, EmeraldMid.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AvatarCircle(
                                        name = usernameSearchState.result.displayName,
                                        photoData = usernameSearchState.result.photoData,
                                        size = 40.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = usernameSearchState.result.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = EmeraldDeep,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "@${usernameSearchState.result.username}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            onAddDirectChat(usernameSearchState.result.username)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                                        modifier = Modifier.testTag("add_found_user_button")
                                    ) {
                                        Text("Add", color = Color.White)
                                    }
                                }
                            }
                        }
                        is UsernameSearchState.NotFound -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("username_not_found_text"),
                                shape = RoundedCornerShape(10.dp),
                                color = ErrorContainerLight
                            ) {
                                Text(
                                    text = usernameSearchState.message,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ErrorRed,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                        else -> Unit
                    }

                    Text(
                        text = "Only accounts actually registered with that exact username can be added.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 60) name = it },
                        label = { Text("Group Name") },
                        placeholder = { Text("e.g., Weekend Trip") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_contact_name_input")
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { raw ->
                            val cleaned = raw.removePrefix("@").lowercase()
                                .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                            if (cleaned.length <= 32) username = cleaned
                        },
                        label = { Text("Group Handle (Optional)") },
                        placeholder = { Text("e.g., weekend_trip") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_contact_username_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isGroup) {
                        onAddGroup(name, username)
                    } else {
                        val found = (usernameSearchState as? UsernameSearchState.Found)?.result
                        onAddDirectChat(found?.username ?: username)
                    }
                },
                enabled = if (isGroup) {
                    name.trim().isNotEmpty()
                } else {
                    cleanUsername.isNotEmpty() &&
                        usernameSearchState !is UsernameSearchState.Loading &&
                        usernameSearchState !is UsernameSearchState.NotFound
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldMid),
                modifier = Modifier.testTag("add_contact_confirm_button")
            ) {
                Text("Add", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onResetSearch()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AppearanceDialog(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectMode(mode)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = {
                                onSelectMode(mode)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = EmeraldMid)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AppLockSetupDialog(
    isCurrentlyEnabled: Boolean,
    onDismiss: () -> Unit,
    onSavePin: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account & Passcode", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Protect Nomi Chat with a 4–6 digit App Lock PIN on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = { Text("New PIN (4–6 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmPin = it },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        pin.length < 4 -> error = "PIN must be at least 4 digits."
                        pin != confirmPin -> error = "PINs do not match."
                        else -> {
                            onSavePin(pin)
                            onDismiss()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark)
            ) {
                Text("Save PIN", color = Color.White)
            }
        },
        dismissButton = {
            Row {
                if (isCurrentlyEnabled) {
                    TextButton(onClick = {
                        onSavePin("")
                        onDismiss()
                    }) {
                        Text("Disable PIN", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun PremiumFeaturesDialog(
    smartAiEnabled: Boolean,
    onToggleSmartAi: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.WorkspacePremium,
                contentDescription = null,
                tint = GoldCrown,
                modifier = Modifier.size(36.dp)
            )
        },
        title = { Text("Nomi Chat Premium", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EmeraldMid)
                    Text(
                        text = "Gemini Smart Replies & contextual reply suggestions are unlocked in your chats.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = EmeraldMid)
                    Text(
                        text = "Real-time Cloud Firestore sync keeps your contacts, groups, and messages backed up.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onToggleSmartAi(!smartAiEnabled)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark)
            ) {
                Text(if (smartAiEnabled) "Pause AI Replies" else "Enable AI Replies", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun AppLockOverlay(
    onVerifyPin: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = EmeraldDark,
                    modifier = Modifier.size(40.dp)
                )
                Text("Nomi Chat Locked", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all(Char::isDigit)) {
                            pin = it
                            wrongPin = false
                        }
                    },
                    label = { Text("Enter App Lock PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (wrongPin) {
                    Text(
                        text = "Incorrect PIN. Try again.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Button(
                    onClick = {
                        if (!onVerifyPin(pin)) {
                            wrongPin = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AvatarPreviewDialog(
    thread: ChatThread,
    hasActiveStory: Boolean = false,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit,
    onViewContact: () -> Unit,
    onViewStory: () -> Unit = {},
    onToggleBlock: () -> Unit
) {
    val isChannel = thread.name.contains("Channel", ignoreCase = true)
    val isGroup = thread.threadType == "group" && !isChannel
    val isOnline = thread.online && !thread.blocked && thread.accountStatus != "banned"
    val statusLabel = when {
        thread.accountStatus == "banned" -> "Banned"
        thread.blocked -> "Blocked"
        isOnline -> "Online"
        else -> "Offline"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(290.dp)
                .testTag("avatar_preview_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp
        ) {
            Column {
                // WhatsApp-style compact avatar header + large preview area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(EmeraldDeep, EmeraldDark, EmeraldMid)
                            )
                        )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarCircle(
                            name = thread.name,
                            photoData = thread.photoData,
                            isGroup = isGroup,
                            isChannel = isChannel,
                            showOnlineDot = isOnline,
                            hasStoryRing = hasActiveStory,
                            size = 132.dp,
                            borderColor = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    // Semi-transparent top name strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.34f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = thread.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (thread.username.isNotBlank()) {
                                Text(
                                    text = "@${thread.username}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MintOnlineGlow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isOnline) EmeraldBright.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) MintOnlineGlow else Color.White.copy(alpha = 0.9f),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Bottom quick-action icon row (Open Chat, View Contact, optional View Story, Block)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenChat,
                        modifier = Modifier.testTag("avatar_preview_open_chat")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Open chat",
                            tint = EmeraldDark
                        )
                    }
                    IconButton(
                        onClick = onViewContact,
                        modifier = Modifier.testTag("avatar_preview_view_contact")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "View contact",
                            tint = EmeraldDark
                        )
                    }
                    if (hasActiveStory) {
                        IconButton(
                            onClick = onViewStory,
                            modifier = Modifier.testTag("avatar_preview_view_story")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DonutLarge,
                                contentDescription = "View 24h story",
                                tint = EmeraldBright
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleBlock,
                        modifier = Modifier.testTag("avatar_preview_block_chat")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = if (thread.blocked) "Unblock" else "Block",
                            tint = ErrorRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactProfileDialog(
    contact: ChatThread,
    onDismiss: () -> Unit,
    onToggleBlock: () -> Unit,
    onReportClick: () -> Unit
) {
    val statusText = when {
        contact.accountStatus == "banned" -> "Banned"
        contact.blocked -> "Blocked"
        contact.online -> "Online"
        else -> "Offline"
    }
    val statusBg = when {
        contact.accountStatus == "banned" || contact.blocked -> ErrorContainerLight
        contact.online -> MintLight
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusFg = when {
        contact.accountStatus == "banned" || contact.blocked -> ErrorRed
        contact.online -> EmeraldDark
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AvatarCircle(
                    name = contact.name,
                    photoData = contact.photoData,
                    isGroup = contact.threadType == "group",
                    showOnlineDot = contact.online && !contact.blocked && contact.accountStatus != "banned",
                    size = 64.dp
                )
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                if (contact.username.isNotBlank()) {
                    Text(
                        text = "@${contact.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = statusBg,
                    modifier = Modifier.testTag("contact_profile_status_badge")
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusFg,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MintLight.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = EmeraldDark,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Messages are end-to-end encrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldDeep
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onToggleBlock,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (contact.blocked) "Unblock" else "Block",
                            color = ErrorRed
                        )
                    }
                    TextButton(
                        onClick = onReportClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.report_user),
                            color = ErrorRed
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark)
            ) {
                Text("Close", color = Color.White)
            }
        }
    )
}

@Composable
fun ReportAccountDialog(
    contact: ChatThread,
    onDismiss: () -> Unit,
    onConfirmReport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = ErrorRed
            )
        },
        title = {
            Text(
                text = "Report ${contact.name}",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Submit a safety report for @${contact.username.ifBlank { contact.id }}. Each user can report once. Accounts reaching $BAN_REPORT_LIMIT unique reports are automatically banned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (contact.accountStatus == "banned") ErrorContainerLight else MintLight
                ) {
                    Text(
                        text = if (contact.accountStatus == "banned") {
                            "Status: Banned (${contact.reportCount}/$BAN_REPORT_LIMIT reports)"
                        } else {
                            "Current reports: ${contact.reportCount} / $BAN_REPORT_LIMIT"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (contact.accountStatus == "banned") ErrorRed else EmeraldDeep,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmReport,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                modifier = Modifier.testTag("confirm_report_button")
            ) {
                Text(stringResource(R.string.report_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun StoryPickerDialog(
    isPosting: Boolean,
    onDismiss: () -> Unit,
    onPostStory: (uri: Uri, caption: String) -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) selectedUri = uri
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) selectedUri = uri
    }

    Dialog(onDismissRequest = { if (!isPosting) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("story_picker_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MintLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DonutLarge,
                                contentDescription = null,
                                tint = EmeraldDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Post 24-Hour Story",
                                style = MaterialTheme.typography.titleMedium,
                                color = EmeraldDeep,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Auto-expires after 24 hours",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (!isPosting) onDismiss() },
                        modifier = Modifier.testTag("story_picker_close")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                if (selectedUri == null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isPosting) {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .testTag("story_pick_gallery_box"),
                        shape = RoundedCornerShape(16.dp),
                        color = MintLight.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, EmeraldMid.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Choose photo",
                                tint = EmeraldDark,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap to choose a photo for your story",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmeraldDeep,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Uses system photo picker — no extra permissions needed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.08f))
                    ) {
                        AsyncImage(
                            model = selectedUri,
                            contentDescription = "Selected story preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !isPosting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("story_pick_photos_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = EmeraldDark,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Photos", color = EmeraldDark, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("image/*"))
                        },
                        enabled = !isPosting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("story_pick_files_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = EmeraldDark,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Files", color = EmeraldDark, maxLines = 1)
                    }
                }

                OutlinedTextField(
                    value = caption,
                    onValueChange = { if (it.length <= 140) caption = it },
                    enabled = !isPosting,
                    label = { Text("Caption (Optional)") },
                    placeholder = { Text("Share a status note...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("story_caption_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isPosting
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val uri = selectedUri ?: return@Button
                            onPostStory(uri, caption)
                        },
                        enabled = selectedUri != null && !isPosting,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("story_post_button")
                    ) {
                        if (isPosting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isPosting) "Posting..." else "Post Story", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun StoryViewerDialog(
    stories: List<StoryItem>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onDeleteStory: (StoryItem) -> Unit
) {
    if (stories.isEmpty()) {
        onDismiss()
        return
    }
    var currentIndex by remember(stories) { mutableIntStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, stories.lastIndex)
    val story = stories[safeIndex]

    val decodedMedia = remember(story.id, story.mediaData) {
        if (story.mediaData.startsWith("data:image")) {
            val b64 = story.mediaData.substringAfter("base64,", "")
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        } else null
    }

    val remainingMs = max(0L, story.expiresAtMs - System.currentTimeMillis())
    val remainingHours = (remainingMs / 3_600_000L).toInt()
    val remainingMinutes = ((remainingMs % 3_600_000L) / 60_000L).toInt()
    val expiryLabel = when {
        remainingHours >= 1 -> "${remainingHours}h left"
        remainingMinutes >= 1 -> "${remainingMinutes}m left"
        else -> "Expiring soon"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 560.dp)
                .testTag("story_viewer_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0E1713),
            shadowElevation = 16.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (decodedMedia != null) {
                    AsyncImage(
                        model = decodedMedia,
                        contentDescription = "Story by ${story.authorName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Invisible tap zones for previous / next story
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable {
                                if (safeIndex > 0) currentIndex = safeIndex - 1
                            }
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable {
                                if (safeIndex < stories.lastIndex) {
                                    currentIndex = safeIndex + 1
                                } else {
                                    onDismiss()
                                }
                            }
                    )
                }

                // Top progress & author header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        stories.forEachIndexed { idx, _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (idx <= safeIndex) EmeraldBright else Color.White.copy(alpha = 0.35f)
                                    )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AvatarCircle(
                                name = story.authorName,
                                photoData = story.authorPhotoData,
                                hasStoryRing = true,
                                size = 38.dp
                            )
                            Column {
                                Text(
                                    text = story.authorName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "@${story.authorUsername} • $expiryLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MintOnlineGlow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (story.userId == currentUserId) {
                                IconButton(
                                    onClick = {
                                        onDeleteStory(story)
                                        if (stories.size <= 1) onDismiss()
                                    },
                                    modifier = Modifier.testTag("story_viewer_delete")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete story",
                                        tint = Color.White
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("story_viewer_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close story",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                if (story.caption.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                                )
                            )
                            .padding(horizontal = 18.dp, vertical = 18.dp)
                    ) {
                        Text(
                            text = story.caption,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}


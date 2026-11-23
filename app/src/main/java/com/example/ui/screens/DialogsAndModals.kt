package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import com.example.data.model.ThemeMode
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.GoldCrown
import com.example.ui.theme.MintLight
import com.example.ui.viewmodel.UsernameSearchState

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
    onAddContact: (
        name: String,
        phone: String,
        username: String,
        isGroup: Boolean,
        peerUid: String,
        isRegisteredPeer: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
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
                        onClick = { isGroup = true },
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
                                val hasPrefix = raw.startsWith("@")
                                val body = raw.removePrefix("@").lowercase()
                                    .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                                val combined = if (hasPrefix) "@$body" else body
                                if (combined.length <= 32) {
                                    username = combined
                                    if (usernameSearchState != UsernameSearchState.Idle) {
                                        onResetSearch()
                                    }
                                }
                            },
                            label = { Text("Username") },
                            placeholder = { Text("e.g. @alex_nomi") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_contact_username_input")
                        )

                        Button(
                            onClick = { onSearchUsername(username) },
                            enabled = cleanUsername.length >= 2 && usernameSearchState !is UsernameSearchState.Loading,
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                                            onAddContact(
                                                usernameSearchState.result.displayName,
                                                "",
                                                usernameSearchState.result.username,
                                                false,
                                                usernameSearchState.result.ownerUid,
                                                true
                                            )
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
                            Text(
                                text = "Username not found in Nomi directory. You can still tap Add below to start an AI chat.",
                                style = MaterialTheme.typography.labelSmall,
                                color = ErrorRed,
                                modifier = Modifier.testTag("username_not_found_text")
                            )
                        }
                        else -> Unit
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 60) name = it },
                        label = { Text("Display Name (Optional)") },
                        placeholder = { Text("Leave blank to derive from username") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_contact_name_input")
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
                    val found = (usernameSearchState as? UsernameSearchState.Found)?.result
                    onAddContact(
                        name.ifBlank { found?.displayName.orEmpty() },
                        phone,
                        found?.username ?: username,
                        isGroup,
                        found?.ownerUid.orEmpty(),
                        found != null
                    )
                },
                enabled = if (isGroup) name.trim().isNotEmpty() else cleanUsername.isNotEmpty(),
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

package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.model.ChatThread
import com.example.data.model.ThemeMode
import com.example.ui.components.AvatarCircle
import com.example.ui.components.EmeraldWaveHeader
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

@Composable
fun EditSingleFieldDialog(
    title: String,
    label: String,
    initialValue: String,
    maxLength: Int,
    sanitizeUsername: Boolean = false,
    isSaving: Boolean = false,
    errorText: String? = null,
    onClearError: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    val cleanVal = if (sanitizeUsername) value.trim().removePrefix("@") else value.trim()
    val isLengthValid = if (sanitizeUsername) cleanVal.length in 2..maxLength else cleanVal.isNotEmpty() && cleanVal.length <= maxLength
    val isFormatValid = if (sanitizeUsername) cleanVal.matches(Regex("^[a-zA-Z0-9_.-]+$")) else true
    val hasError = !errorText.isNullOrBlank()
    val isValid = cleanVal.isNotEmpty() && isLengthValid && isFormatValid && !hasError
    val isInvalid = hasError || (cleanVal.isNotEmpty() && (!isLengthValid || !isFormatValid))

    val borderColor = when {
        isInvalid -> ErrorRed
        isValid -> Color(0xFF16A34A)
        else -> MaterialTheme.colorScheme.outline
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        onClearError()
                        val cleaned = if (sanitizeUsername) {
                            raw.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                        } else raw
                        value = cleaned.take(maxLength)
                    },
                    label = { Text(label) },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = isInvalid,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = borderColor,
                        unfocusedBorderColor = borderColor,
                        errorBorderColor = ErrorRed,
                        focusedLabelColor = if (isValid) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                        cursorColor = EmeraldMid
                    ),
                    trailingIcon = {
                        if (isValid) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Valid input",
                                tint = Color(0xFF16A34A),
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (isInvalid && (cleanVal.isNotEmpty() || hasError)) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Invalid input",
                                tint = ErrorRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_field_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        if (hasError) {
                            Text(
                                text = errorText.orEmpty(),
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("edit_field_error")
                            )
                        } else if (cleanVal.isNotEmpty()) {
                            if (!isLengthValid && sanitizeUsername) {
                                Text(
                                    text = "Minimum 2 characters",
                                    color = ErrorRed,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (!isFormatValid && sanitizeUsername) {
                                Text(
                                    text = "Letters, numbers, _, ., - only",
                                    color = ErrorRed,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (isValid && sanitizeUsername) {
                                Text(
                                    text = "Valid username",
                                    color = Color(0xFF16A34A),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Text(
                        text = "${cleanVal.length}/$maxLength",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            cleanVal.length >= maxLength -> ErrorRed
                            isValid -> Color(0xFF16A34A)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (cleanVal.length >= maxLength) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("edit_field_char_count")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(value) },
                enabled = !isSaving && isValid,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                modifier = Modifier.testTag("edit_field_save_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddContactDialog(
    usernameSearchState: UsernameSearchState = UsernameSearchState.Idle,
    offlinePopupMessage: String? = null,
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
                    val cleanInputUsername = username.trim().removePrefix("@")
                    val isInputUsernameLengthValid = cleanInputUsername.length in 2..40
                    val isInputUsernameFormatValid = cleanInputUsername.matches(Regex("^[a-zA-Z0-9_.-]+$"))
                    val isSearchNotFound = usernameSearchState is UsernameSearchState.NotFound
                    val isInputUsernameValid = cleanInputUsername.isNotEmpty() && isInputUsernameLengthValid && isInputUsernameFormatValid && !isSearchNotFound
                    val isInputUsernameInvalid = isSearchNotFound || (cleanInputUsername.isNotEmpty() && (!isInputUsernameLengthValid || !isInputUsernameFormatValid))

                    val usernameBorderColor = when {
                        isInputUsernameInvalid -> ErrorRed
                        isInputUsernameValid -> Color(0xFF16A34A)
                        else -> MaterialTheme.colorScheme.outline
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { raw ->
                                val clean = raw.replace("\n", "").take(40)
                                username = clean
                                if (usernameSearchState != UsernameSearchState.Idle) {
                                    onResetSearch()
                                }
                            },
                            label = { Text("Username") },
                            placeholder = { Text("Enter exact @username") },
                            singleLine = true,
                            isError = isInputUsernameInvalid,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = usernameBorderColor,
                                unfocusedBorderColor = usernameBorderColor,
                                errorBorderColor = ErrorRed,
                                focusedLabelColor = if (isInputUsernameValid) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                                cursorColor = EmeraldMid
                            ),
                            trailingIcon = {
                                if (isInputUsernameValid) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Valid username",
                                        tint = Color(0xFF16A34A),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else if (isInputUsernameInvalid && cleanInputUsername.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Invalid username",
                                        tint = ErrorRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_contact_username_input")
                        )

                        Button(
                            onClick = { onSearchUsername(username) },
                            enabled = usernameSearchState !is UsernameSearchState.Loading && cleanInputUsername.length in 2..40,
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (cleanInputUsername.isNotEmpty() && !isInputUsernameLengthValid) {
                            Text(
                                text = "Username must be 2–40 characters",
                                color = ErrorRed,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (cleanInputUsername.isNotEmpty() && !isInputUsernameFormatValid) {
                            Text(
                                text = "Letters, numbers, _, ., - only",
                                color = ErrorRed,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (isInputUsernameValid) {
                            Text(
                                text = "Valid format",
                                color = Color(0xFF16A34A),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f, fill = false))
                        }

                        Text(
                            text = "${cleanInputUsername.length}/40",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                cleanInputUsername.length >= 40 -> ErrorRed
                                isInputUsernameValid -> Color(0xFF16A34A)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (cleanInputUsername.length >= 40) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.testTag("add_contact_char_count")
                        )
                    }

                    when (usernameSearchState) {
                        is UsernameSearchState.Loading -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("username_checking_card"),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = EmeraldMid
                                    )
                                    Text(
                                        text = "Checking username...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("username_checking_text")
                                    )
                                }
                            }
                        }
                        is UsernameSearchState.Found -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("found_user_card"),
                                shape = RoundedCornerShape(12.dp),
                                color = MintLight,
                                border = BorderStroke(1.dp, EmeraldMid.copy(alpha = 0.45f))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    if (!usernameSearchState.result.bannerData.isNullOrBlank()) {
                                        EmeraldWaveHeader(
                                            bannerData = usernameSearchState.result.bannerData,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(64.dp)
                                        ) {}
                                    }
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = usernameSearchState.result.displayName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = EmeraldDeep,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (usernameSearchState.result.verified) {
                                                    Icon(
                                                        imageVector = Icons.Default.Verified,
                                                        contentDescription = "Verified account",
                                                        tint = Color(0xFF1E9BF0),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        Text(
                                            text = "@${usernameSearchState.result.username}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (usernameSearchState.result.bio.isNotBlank()) {
                                            Text(
                                                text = usernameSearchState.result.bio,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = EmeraldDark,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
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

                    val cleanGroupHandle = username.removePrefix("@").lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                    val isGroupHandleTooShort = cleanGroupHandle.isNotEmpty() && cleanGroupHandle.length < 2
                    val isGroupHandleValid = cleanGroupHandle.length in 2..32
                    val groupHandleBorderColor = when {
                        isGroupHandleTooShort -> ErrorRed
                        isGroupHandleValid -> Color(0xFF16A34A)
                        else -> MaterialTheme.colorScheme.outline
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = { raw ->
                            val cleaned = raw.removePrefix("@").lowercase()
                                .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                            username = cleaned.take(32)
                        },
                        label = { Text("Group Handle (Optional)") },
                        placeholder = { Text("e.g., weekend_trip") },
                        singleLine = true,
                        isError = isGroupHandleTooShort,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = groupHandleBorderColor,
                            unfocusedBorderColor = groupHandleBorderColor,
                            errorBorderColor = ErrorRed,
                            focusedLabelColor = if (isGroupHandleValid) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                            cursorColor = EmeraldMid
                        ),
                        trailingIcon = {
                            if (isGroupHandleValid) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Valid handle",
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(20.dp)
                                )
                            } else if (isGroupHandleTooShort) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Handle too short",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_contact_username_input")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isGroupHandleTooShort) {
                            Text(
                                text = "Handle must be at least 2 characters",
                                color = ErrorRed,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f, fill = false))
                        }

                        Text(
                            text = "${cleanGroupHandle.length}/32",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                cleanGroupHandle.length >= 32 -> ErrorRed
                                isGroupHandleValid -> Color(0xFF16A34A)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (cleanGroupHandle.length >= 32) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.testTag("add_group_handle_char_count")
                        )
                    }
                }

                if (!offlinePopupMessage.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_contact_offline_popup"),
                        shape = RoundedCornerShape(12.dp),
                        color = ErrorContainerLight,
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = offlinePopupMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
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
    storyRingSeen: Boolean = false,
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
                // Dedicated top header bar cleanly separated from circular avatar showcase
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(EmeraldDeep)
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

                // Spacious circular profile showcase displaying the full photo cleanly with live banner backdrop
                EmeraldWaveHeader(
                    bannerData = thread.bannerData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(236.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AvatarCircle(
                            name = thread.name,
                            photoData = thread.photoData,
                            isGroup = isGroup,
                            isChannel = isChannel,
                            showOnlineDot = isOnline,
                            hasStoryRing = hasActiveStory,
                            storyRingSeen = storyRingSeen,
                            size = 188.dp,
                            borderColor = Color.White.copy(alpha = 0.85f),
                            imageContentScale = ContentScale.Fit,
                            imageBackgroundColor = Color(0xFF0A241A),
                            modifier = Modifier.testTag("avatar_preview_circle")
                        )
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
                EmeraldWaveHeader(
                    bannerData = contact.bannerData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {}
                AvatarCircle(
                    name = contact.name,
                    photoData = contact.photoData,
                    isGroup = contact.threadType == "group",
                    showOnlineDot = contact.online && !contact.blocked && contact.accountStatus != "banned",
                    size = 84.dp,
                    borderColor = EmeraldMid.copy(alpha = 0.45f),
                    imageContentScale = ContentScale.Fit,
                    imageBackgroundColor = Color(0xFF0A241A)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    if (contact.verified) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified account",
                            tint = Color(0xFF1E9BF0),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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
                if (contact.bio.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_profile_bio")
                    ) {
                        Text(
                            text = contact.bio,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
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
    onConfirmReport: (String) -> Unit
) {
    val reasons = remember { listOf("Spam", "Abuse", "Fake Account", "Harassment", "Other") }
    var selectedReason by remember { mutableStateOf("Spam") }

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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Submit a safety report for @${contact.username.ifBlank { contact.id }}. Our moderation team will review this account for policy violations. Select a reason:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("report_dialog_message")
                )
                reasons.forEach { reason ->
                    val isSelected = selectedReason == reason
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) ErrorRed.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .clickable { selectedReason = reason }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("report_reason_${reason.lowercase().replace(" ", "_")}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedReason = reason },
                            colors = RadioButtonDefaults.colors(selectedColor = ErrorRed)
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmReport(selectedReason) },
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
fun EditProfileDialog(
    initialDisplayName: String,
    initialBio: String,
    initialUsername: String,
    initialDob: String,
    isSaving: Boolean,
    errorText: String? = null,
    onClearError: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (displayName: String, bio: String, username: String, dob: String) -> Unit
) {
    var displayName by remember(initialDisplayName) { mutableStateOf(initialDisplayName) }
    var bio by remember(initialBio) { mutableStateOf(initialBio) }
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var dob by remember(initialDob) { mutableStateOf(initialDob) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val shownError = validationError ?: errorText

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.edit_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it.take(60)
                        validationError = null
                        onClearError()
                    },
                    label = { Text("Display Name") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_profile_name_input")
                )
                val cleanProfileUser = username.trim().removePrefix("@")
                val isProfileUserLengthValid = cleanProfileUser.length in 2..40
                val isProfileUserFormatValid = cleanProfileUser.matches(Regex("^[a-zA-Z0-9_.-]+$"))
                val hasProfileUserError = !shownError.isNullOrBlank() && (shownError.contains("username", ignoreCase = true) || shownError.contains("taken", ignoreCase = true))
                val isProfileUserValid = cleanProfileUser.isNotEmpty() && isProfileUserLengthValid && isProfileUserFormatValid && !hasProfileUserError
                val isProfileUserInvalid = hasProfileUserError || (cleanProfileUser.isNotEmpty() && (!isProfileUserLengthValid || !isProfileUserFormatValid))

                val profileUserBorderColor = when {
                    isProfileUserInvalid -> ErrorRed
                    isProfileUserValid -> Color(0xFF16A34A)
                    else -> MaterialTheme.colorScheme.outline
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { raw ->
                        val cleaned = raw.removePrefix("@").lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                        username = cleaned.take(40)
                        validationError = null
                        onClearError()
                    },
                    label = { Text("Username") },
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = isProfileUserInvalid,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = profileUserBorderColor,
                        unfocusedBorderColor = profileUserBorderColor,
                        errorBorderColor = ErrorRed,
                        focusedLabelColor = if (isProfileUserValid) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                        cursorColor = EmeraldMid
                    ),
                    trailingIcon = {
                        if (isProfileUserValid) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Valid username",
                                tint = Color(0xFF16A34A),
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (isProfileUserInvalid && cleanProfileUser.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Invalid username",
                                tint = ErrorRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_profile_username_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cleanProfileUser.isNotEmpty() && !isProfileUserLengthValid) {
                        Text(
                            text = "Username must be 2–40 characters",
                            color = ErrorRed,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else if (cleanProfileUser.isNotEmpty() && !isProfileUserFormatValid) {
                        Text(
                            text = "Letters, numbers, _, ., - only",
                            color = ErrorRed,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else if (isProfileUserValid) {
                        Text(
                            text = "Valid username",
                            color = Color(0xFF16A34A),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f, fill = false))
                    }

                    Text(
                        text = "${cleanProfileUser.length}/40",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            cleanProfileUser.length >= 40 -> ErrorRed
                            isProfileUserValid -> Color(0xFF16A34A)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (cleanProfileUser.length >= 40) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.testTag("edit_profile_username_char_count")
                    )
                }
                OutlinedTextField(
                    value = bio,
                    onValueChange = {
                        bio = it.take(200)
                        validationError = null
                        onClearError()
                    },
                    label = { Text(stringResource(R.string.bio_title)) },
                    minLines = 2,
                    maxLines = 3,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_profile_bio_input")
                )
                OutlinedTextField(
                    value = dob,
                    onValueChange = {
                        dob = it.take(20)
                        validationError = null
                        onClearError()
                    },
                    label = { Text("Date of Birth (YYYY-MM-DD)") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_profile_dob_input")
                )
                if (!shownError.isNullOrBlank()) {
                    Text(
                        text = shownError,
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("edit_profile_error")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = displayName.trim()
                    val cleanUser = username.trim().removePrefix("@")
                    val cleanDob = dob.trim()
                    when {
                        cleanName.isEmpty() -> validationError = "Display name cannot be empty."
                        cleanUser.length < 2 -> validationError = "Username must be at least 2 characters."
                        cleanDob.length < 4 -> validationError = "Enter a valid date of birth."
                        else -> onSave(cleanName, bio.trim(), cleanUser, cleanDob)
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                modifier = Modifier.testTag("edit_profile_save_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ChangePasswordDialog(
    requireCurrentPassword: Boolean,
    isChanging: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val transformation = if (showPasswords) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { if (!isChanging) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = EmeraldDark
            )
        },
        title = { Text(stringResource(R.string.change_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = {
                        currentPassword = it
                        validationError = null
                    },
                    label = {
                        Text(
                            if (requireCurrentPassword) "Current Password"
                            else "Current Password (if already set)"
                        )
                    },
                    singleLine = true,
                    enabled = !isChanging,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPasswords = !showPasswords }) {
                            Icon(
                                imageVector = if (showPasswords) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPasswords) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_password_current_input")
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        validationError = null
                    },
                    label = { Text("New Password") },
                    singleLine = true,
                    enabled = !isChanging,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_password_new_input")
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        validationError = null
                    },
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    enabled = !isChanging,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_password_confirm_input")
                )
                if (validationError != null) {
                    Text(
                        text = validationError.orEmpty(),
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("change_password_error")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        requireCurrentPassword && currentPassword.isEmpty() ->
                            validationError = "Enter your current password."
                        newPassword.length < 6 ->
                            validationError = "New password must be at least 6 characters."
                        newPassword != confirmPassword ->
                            validationError = "New passwords do not match."
                        else -> onSubmit(currentPassword, newPassword)
                    }
                },
                enabled = !isChanging,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                modifier = Modifier.testTag("change_password_submit_button")
            ) {
                if (isChanging) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isChanging) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}





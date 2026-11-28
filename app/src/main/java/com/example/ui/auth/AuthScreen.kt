package com.example.ui.auth

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.AuthFlowMode
import com.example.data.network.NetworkStatus
import com.example.data.repository.NomiChatRepository
import com.example.data.security.PendingAuthCredentials
import com.example.ui.components.EmeraldWaveHeader
import com.example.ui.components.USERNAME_CHAR_REGEX
import com.example.ui.components.USERNAME_FORMAT_REGEX
import com.example.ui.components.UsernameValidationMessage
import com.example.ui.components.sanitizeUsernameInput
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.ErrorContainerLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LoginGreenEnd
import com.example.ui.theme.LoginGreenStart
import com.example.ui.theme.LoginInputBorder
import com.example.ui.theme.LoginLabelDark
import com.example.ui.theme.MintLight
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class AuthTab {
    SIGN_UP,
    LOGIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    initialErrorMessage: String? = null,
    initialIsLoginTab: Boolean = false,
    onDismissInitialError: () -> Unit = {},
    onSavePreferredIdentity: suspend (
        displayName: String,
        username: String,
        dob: String,
        authFlowMode: AuthFlowMode,
        returningIdentifier: String
    ) -> Unit,
    onAuthSuccess: () -> Unit,
    networkStatus: NetworkStatus = NetworkStatus()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var activeTab by remember(initialIsLoginTab) {
        mutableStateOf(if (initialIsLoginTab) AuthTab.LOGIN else AuthTab.SIGN_UP)
    }
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var signupPassword by remember { mutableStateOf("") }
    var signupRePassword by remember { mutableStateOf("") }
    var showSignupPassword by remember { mutableStateOf(false) }
    var showSignupRePassword by remember { mutableStateOf(false) }

    var loginIdentifier by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var showLoginPassword by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember(initialErrorMessage) { mutableStateOf(initialErrorMessage) }
    var showDatePicker by remember { mutableStateOf(false) }
    var signUpInvalidCharWarning by remember { mutableStateOf(false) }
    var signUpShakeCount by remember { mutableIntStateOf(0) }
    var loginInvalidCharWarning by remember { mutableStateOf(false) }
    var loginShakeCount by remember { mutableIntStateOf(0) }

    fun clearError() {
        if (errorMessage != null) {
            errorMessage = null
            onDismissInitialError()
        }
    }

    // Dedicated handler ONLY for the "Sign in with Google" button
    fun launchGoogleAuth(
        targetName: String,
        targetUsername: String,
        targetDob: String,
        mode: AuthFlowMode,
        returningId: String
    ) {
        if (!networkStatus.isConnected) {
            errorMessage = "No internet connection. Connect to Wi-Fi or mobile data and try again."
            return
        }
        isLoading = true
        clearError()
        scope.launch {
            try {
                onSavePreferredIdentity(
                    targetName,
                    targetUsername,
                    targetDob,
                    mode,
                    returningId
                )
                onGoogleSignInClicked(
                    context = context,
                    credentialManager = credentialManager,
                    onAuthSuccess = {
                        isLoading = false
                        onAuthSuccess()
                    },
                    onAuthError = { msg ->
                        PendingAuthCredentials.clear()
                        isLoading = false
                        errorMessage = msg
                    },
                    scope = scope,
                    onAuthCancelled = {
                        PendingAuthCredentials.clear()
                        isLoading = false
                    }
                )
            } catch (e: Exception) {
                PendingAuthCredentials.clear()
                isLoading = false
                errorMessage = e.localizedMessage ?: "Authentication failed. Please try again."
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
        ) {
            EmeraldWaveHeader(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Nomi Chat Logo",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.brand_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    val heroRequest = remember(context) {
                        ImageRequest.Builder(context)
                            .data(R.drawable.img_auth_hero)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = heroRequest,
                        contentDescription = "Nomi Chat glass messaging artwork",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(128.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 24.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AuthModeSegment(
                                label = "Sign Up",
                                selected = activeTab == AuthTab.SIGN_UP,
                                testTag = "auth_tab_signup",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    activeTab = AuthTab.SIGN_UP
                                    PendingAuthCredentials.clear()
                                    clearError()
                                }
                            )
                            AuthModeSegment(
                                label = "Returning Login",
                                selected = activeTab == AuthTab.LOGIN,
                                testTag = "auth_tab_login",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    activeTab = AuthTab.LOGIN
                                    PendingAuthCredentials.clear()
                                    clearError()
                                }
                            )
                        }
                    }

                    if (activeTab == AuthTab.SIGN_UP) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = {
                                displayName = it
                                clearError()
                            },
                            label = { Text("Full Name") },
                            placeholder = { Text("Enter your full name") },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null, tint = EmeraldMid)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldMid,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_display_name_input")
                        )

                        val cleanSignUpUser = username.removePrefix("@")
                        val isSignUpUserLengthValid = cleanSignUpUser.length in 2..40
                        val isSignUpUserFormatValid = cleanSignUpUser.matches(USERNAME_CHAR_REGEX)
                        val hasSignUpUsernameError = errorMessage != null && (errorMessage!!.contains("username", ignoreCase = true) || errorMessage!!.contains("taken", ignoreCase = true))
                        val isSignUpUserValid = cleanSignUpUser.isNotEmpty() && isSignUpUserLengthValid && isSignUpUserFormatValid && !hasSignUpUsernameError && !signUpInvalidCharWarning
                        val isSignUpUserInvalid = hasSignUpUsernameError || signUpInvalidCharWarning || (cleanSignUpUser.isNotEmpty() && (!isSignUpUserLengthValid || !isSignUpUserFormatValid))

                        val signUpUserBorderColor = when {
                            isSignUpUserInvalid -> ErrorRed
                            isSignUpUserValid -> Color(0xFF16A34A)
                            else -> MaterialTheme.colorScheme.outline
                        }

                        OutlinedTextField(
                            value = username,
                            onValueChange = { raw ->
                                val sanitized = sanitizeUsernameInput(
                                    raw = raw,
                                    maxLength = 40,
                                    allowLeadingAt = false,
                                    forceLowercase = true
                                )
                                username = sanitized.value
                                signUpInvalidCharWarning = sanitized.hadDisallowedInput
                                if (sanitized.hadDisallowedInput) {
                                    signUpShakeCount++
                                }
                                clearError()
                            },
                            label = { Text("Username (Unique)") },
                            placeholder = { Text("Choose a unique username") },
                            leadingIcon = {
                                Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = EmeraldMid)
                            },
                            trailingIcon = {
                                if (isSignUpUserValid) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Valid username",
                                        tint = Color(0xFF16A34A),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else if (isSignUpUserInvalid && (cleanSignUpUser.isNotEmpty() || signUpInvalidCharWarning)) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Invalid username",
                                        tint = ErrorRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            isError = isSignUpUserInvalid,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = signUpUserBorderColor,
                                unfocusedBorderColor = signUpUserBorderColor,
                                errorBorderColor = ErrorRed,
                                focusedLabelColor = if (isSignUpUserValid) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                                cursorColor = EmeraldMid
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_username_input")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val signUpUserMsg: String?
                            val signUpUserIsError: Boolean
                            val signUpUserIsSuccess: Boolean
                            when {
                                signUpInvalidCharWarning -> {
                                    signUpUserMsg = "No spaces or special characters allowed (letters, numbers, _, ., - only)"
                                    signUpUserIsError = true
                                    signUpUserIsSuccess = false
                                }
                                cleanSignUpUser.isNotEmpty() && !isSignUpUserLengthValid -> {
                                    signUpUserMsg = "Username must be 2–40 characters"
                                    signUpUserIsError = true
                                    signUpUserIsSuccess = false
                                }
                                cleanSignUpUser.isNotEmpty() && !isSignUpUserFormatValid -> {
                                    signUpUserMsg = "No spaces or special characters allowed (letters, numbers, _, ., - only)"
                                    signUpUserIsError = true
                                    signUpUserIsSuccess = false
                                }
                                isSignUpUserValid -> {
                                    signUpUserMsg = "Valid username format"
                                    signUpUserIsError = false
                                    signUpUserIsSuccess = true
                                }
                                else -> {
                                    signUpUserMsg = null
                                    signUpUserIsError = false
                                    signUpUserIsSuccess = false
                                }
                            }

                            UsernameValidationMessage(
                                message = signUpUserMsg,
                                isError = signUpUserIsError,
                                isSuccess = signUpUserIsSuccess,
                                shakeTrigger = signUpShakeCount,
                                testTag = "username_message",
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            Text(
                                text = "${cleanSignUpUser.length}/40",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    cleanSignUpUser.length >= 40 -> ErrorRed
                                    isSignUpUserValid -> Color(0xFF16A34A)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (cleanSignUpUser.length >= 40) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .testTag("auth_username_char_count")
                            )
                        }

                        OutlinedTextField(
                            value = dob,
                            onValueChange = {
                                dob = it.filter { ch -> ch.isDigit() || ch == '-' || ch == '/' }.take(12)
                                clearError()
                            },
                            label = { Text("Date of Birth") },
                            placeholder = { Text("YYYY-MM-DD") },
                            leadingIcon = {
                                Icon(Icons.Default.Cake, contentDescription = null, tint = EmeraldMid)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showDatePicker = true },
                                    modifier = Modifier.testTag("auth_dob_picker_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarMonth,
                                        contentDescription = "Pick date of birth",
                                        tint = EmeraldDark
                                    )
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldMid,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_dob_input")
                        )

                        OutlinedTextField(
                            value = signupPassword,
                            onValueChange = {
                                signupPassword = it
                                clearError()
                            },
                            label = { Text("Password") },
                            placeholder = { Text("Create a password") },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldMid)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showSignupPassword = !showSignupPassword },
                                    modifier = Modifier.testTag("auth_signup_password_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (showSignupPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showSignupPassword) "Hide password" else "Show password",
                                        tint = EmeraldDark
                                    )
                                }
                            },
                            visualTransformation = if (showSignupPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldMid,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_signup_password_input")
                        )

                        OutlinedTextField(
                            value = signupRePassword,
                            onValueChange = {
                                signupRePassword = it
                                clearError()
                            },
                            label = { Text("Re-enter Password") },
                            placeholder = { Text("Re-enter your password") },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldMid)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showSignupRePassword = !showSignupRePassword },
                                    modifier = Modifier.testTag("auth_signup_repassword_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (showSignupRePassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showSignupRePassword) "Hide password" else "Show password",
                                        tint = EmeraldDark
                                    )
                                }
                            },
                            visualTransformation = if (showSignupRePassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldMid,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_signup_repassword_input")
                        )
                    } else {
                        val loginLabelColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            LoginLabelDark
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_login_box")
                        ) {
                            Text(
                                text = "Username",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = loginLabelColor,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            val cleanLoginId = loginIdentifier.removePrefix("@")
                            val isLoginIdLengthValid = cleanLoginId.length in 2..40
                            val isLoginIdFormatValid = cleanLoginId.matches(USERNAME_CHAR_REGEX)
                            val hasLoginError = errorMessage != null && (errorMessage!!.contains("user", ignoreCase = true) || errorMessage!!.contains("found", ignoreCase = true))
                            val isLoginIdValid = cleanLoginId.isNotEmpty() && isLoginIdLengthValid && isLoginIdFormatValid && !hasLoginError && !loginInvalidCharWarning
                            val isLoginIdInvalid = hasLoginError || loginInvalidCharWarning || (cleanLoginId.isNotEmpty() && (!isLoginIdLengthValid || !isLoginIdFormatValid))

                            val loginIdBorderColor = when {
                                isLoginIdInvalid -> ErrorRed
                                isLoginIdValid -> Color(0xFF16A34A)
                                else -> LoginInputBorder
                            }

                            OutlinedTextField(
                                value = loginIdentifier,
                                onValueChange = { raw ->
                                    val sanitized = sanitizeUsernameInput(
                                        raw = raw,
                                        maxLength = 40,
                                        allowLeadingAt = true,
                                        forceLowercase = true
                                    )
                                    loginIdentifier = sanitized.value
                                    loginInvalidCharWarning = sanitized.hadDisallowedInput
                                    if (sanitized.hadDisallowedInput) {
                                        loginShakeCount++
                                    }
                                    clearError()
                                },
                                placeholder = { Text("Enter your username") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AlternateEmail,
                                        contentDescription = null,
                                        tint = if (isLoginIdValid) Color(0xFF16A34A) else LoginGreenStart
                                    )
                                },
                                trailingIcon = {
                                    if (isLoginIdValid) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Valid identifier",
                                            tint = Color(0xFF16A34A),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else if (isLoginIdInvalid && (cleanLoginId.isNotEmpty() || loginInvalidCharWarning)) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Invalid identifier",
                                            tint = ErrorRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                singleLine = true,
                                isError = isLoginIdInvalid,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = loginIdBorderColor,
                                    unfocusedBorderColor = loginIdBorderColor,
                                    errorBorderColor = ErrorRed,
                                    cursorColor = LoginGreenStart
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_login_identifier_input")
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val loginUserMsg: String?
                                val loginUserIsError: Boolean
                                val loginUserIsSuccess: Boolean
                                when {
                                    loginInvalidCharWarning -> {
                                        loginUserMsg = "No spaces or special characters allowed (letters, numbers, _, ., - only)"
                                        loginUserIsError = true
                                        loginUserIsSuccess = false
                                    }
                                    cleanLoginId.isNotEmpty() && !isLoginIdLengthValid -> {
                                        loginUserMsg = "Username must be 2–40 characters"
                                        loginUserIsError = true
                                        loginUserIsSuccess = false
                                    }
                                    cleanLoginId.isNotEmpty() && !isLoginIdFormatValid -> {
                                        loginUserMsg = "No spaces or special characters allowed (letters, numbers, _, ., - only)"
                                        loginUserIsError = true
                                        loginUserIsSuccess = false
                                    }
                                    isLoginIdValid -> {
                                        loginUserMsg = "Valid format"
                                        loginUserIsError = false
                                        loginUserIsSuccess = true
                                    }
                                    else -> {
                                        loginUserMsg = null
                                        loginUserIsError = false
                                        loginUserIsSuccess = false
                                    }
                                }

                                UsernameValidationMessage(
                                    message = loginUserMsg,
                                    isError = loginUserIsError,
                                    isSuccess = loginUserIsSuccess,
                                    shakeTrigger = loginShakeCount,
                                    testTag = "username_message",
                                    modifier = Modifier.weight(1f, fill = false)
                                )

                                Text(
                                    text = "${cleanLoginId.length}/40",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        cleanLoginId.length >= 40 -> ErrorRed
                                        isLoginIdValid -> Color(0xFF16A34A)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (cleanLoginId.length >= 40) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .testTag("auth_login_identifier_char_count")
                                )
                            }

                            Text(
                                text = "Password",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = loginLabelColor,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            OutlinedTextField(
                                value = loginPassword,
                                onValueChange = {
                                    loginPassword = it
                                    clearError()
                                },
                                placeholder = { Text("Enter your password") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = LoginGreenStart
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showLoginPassword = !showLoginPassword },
                                        modifier = Modifier.testTag("auth_login_password_toggle")
                                    ) {
                                        Icon(
                                            imageVector = if (showLoginPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (showLoginPassword) "Hide password" else "Show password",
                                            tint = LoginGreenStart
                                        )
                                    }
                                },
                                visualTransformation = if (showLoginPassword) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = LoginGreenStart,
                                    unfocusedBorderColor = LoginInputBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_login_password_input")
                            )
                        }
                    }

                    if (errorMessage != null) {
                        Surface(
                            color = ErrorContainerLight,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.2.dp, ErrorRed.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_error_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = errorMessage.orEmpty(),
                                    color = ErrorRed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    val primaryButtonShape = if (activeTab == AuthTab.LOGIN) {
                        RoundedCornerShape(12.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    }
                    val primaryButtonBrush = if (activeTab == AuthTab.LOGIN) {
                        Brush.horizontalGradient(listOf(LoginGreenStart, LoginGreenEnd))
                    } else {
                        Brush.horizontalGradient(listOf(EmeraldDark, EmeraldBright))
                    }

                    Button(
                        onClick = {
                            if (activeTab == AuthTab.SIGN_UP) {
                                val cleanName = displayName.trim()
                                val cleanUser = NomiChatRepository.normalizeUsername(username)
                                val cleanDob = dob.trim()
                                if (cleanName.isEmpty() || cleanUser.isEmpty() || cleanDob.isEmpty() ||
                                    signupPassword.isEmpty() || signupRePassword.isEmpty()
                                ) {
                                    errorMessage = "Please fill in all fields."
                                    return@Button
                                }
                                if (signupPassword != signupRePassword) {
                                    errorMessage = "Passwords do not match."
                                    return@Button
                                }
                                if (cleanUser.length < 2) {
                                    errorMessage = "Username must be at least 2 characters."
                                    signUpShakeCount++
                                    return@Button
                                }
                                if (!USERNAME_FORMAT_REGEX.matches(cleanUser)) {
                                    errorMessage = "Username cannot contain spaces or special characters."
                                    signUpShakeCount++
                                    return@Button
                                }
                                PendingAuthCredentials.setPassword(signupPassword)
                                launchGoogleAuth(
                                    targetName = cleanName,
                                    targetUsername = cleanUser,
                                    targetDob = cleanDob,
                                    mode = AuthFlowMode.SIGN_UP,
                                    returningId = ""
                                )
                            } else {
                                val cleanUser = NomiChatRepository.normalizeUsername(loginIdentifier)
                                if (cleanUser.isEmpty() || loginPassword.isEmpty()) {
                                    errorMessage = "Please enter both username and password."
                                    return@Button
                                }
                                if (!USERNAME_FORMAT_REGEX.matches(cleanUser)) {
                                    errorMessage = "Username cannot contain spaces or special characters."
                                    loginShakeCount++
                                    return@Button
                                }
                                PendingAuthCredentials.setPassword(loginPassword)
                                launchGoogleAuth(
                                    targetName = "",
                                    targetUsername = "",
                                    targetDob = "",
                                    mode = AuthFlowMode.RETURNING_LOGIN,
                                    returningId = cleanUser
                                )
                            }
                        },
                        enabled = !isLoading,
                        shape = primaryButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(
                                brush = primaryButtonBrush,
                                shape = primaryButtonShape
                            )
                            .testTag(if (activeTab == AuthTab.SIGN_UP) "auth_signup_button" else "auth_login_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.signing_in),
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = if (activeTab == AuthTab.SIGN_UP) "Continue to Nomi Chat" else "Log In",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                        Text(
                            text = "or",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                    }

                    OutlinedButton(
                        onClick = {
                            PendingAuthCredentials.clear()
                            val desiredHandle = if (activeTab == AuthTab.SIGN_UP) {
                                NomiChatRepository.normalizeUsername(username)
                            } else {
                                ""
                            }
                            launchGoogleAuth(
                                targetName = displayName.trim(),
                                targetUsername = desiredHandle,
                                targetDob = dob.trim().ifBlank { "2000-01-01" },
                                mode = AuthFlowMode.GOOGLE_QUICK,
                                returningId = ""
                            )
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, EmeraldMid.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("google_sign_in_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MintLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "G",
                                color = EmeraldDeep,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.sign_in_google),
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldDeep,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MintLight.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = EmeraldDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (networkStatus.isConnected) {
                                "Unique @username registry, Google Sign-In & Cloud Firestore sync (${networkStatus.transportLabel})."
                            } else {
                                "Offline mode: connect Wi-Fi or mobile data for Google Sign-In & cross-device sync."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldDeep,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            dob = formatter.format(Date(millis))
                            clearError()
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.save), color = EmeraldDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun AuthModeSegment(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) EmeraldDark else Color.Transparent
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.SmartReplyRepository
import com.example.data.local.LocalAuthOutcome
import com.example.data.local.LocalAuthStore
import com.example.data.local.LocalNomiRepository
import com.example.data.model.AuthFlowMode
import com.example.data.model.ChatPillFilter
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.MainBottomTab
import com.example.data.model.ThemeMode
import com.example.data.model.UiState
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.preferences.UserAppPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.NomiChatRepository
import com.example.firebase.configureAppCheck
import com.example.notifications.MessageNotificationHelper
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.onGoogleSignInClicked
import com.example.ui.auth.signOutUser
import com.example.ui.components.NomiBottomBar
import com.example.ui.components.SmallFloatingPopup
import com.example.ui.screens.AddContactDialog
import com.example.ui.screens.AdminModerationScreen
import com.example.ui.screens.AppLockOverlay
import com.example.ui.screens.AppLockSetupDialog
import com.example.ui.screens.AppearanceDialog
import com.example.ui.screens.ArchivedChatsScreen
import com.example.ui.screens.AvatarPreviewDialog
import com.example.ui.screens.BannedAccountAppealScreen
import com.example.ui.screens.ChangePasswordDialog
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ContactProfileDialog
import com.example.ui.screens.ConversationScreen
import com.example.ui.screens.EditProfileDialog
import com.example.ui.screens.EditSingleFieldDialog
import com.example.ui.screens.InAppAdminAlertBanner
import com.example.ui.screens.PremiumFeaturesDialog
import com.example.ui.screens.PrivacySettingsScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ReportAccountDialog
import com.example.ui.screens.StoryEditorScreen
import com.example.ui.screens.StoryViewerScreen
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.MintLight
import com.example.ui.theme.MintOnlineGlow
import com.example.ui.theme.NomiChatTheme
import com.example.ui.viewmodel.NomiChatViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.example.data.security.PendingAuthCredentials
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationContactIdState by mutableStateOf<String?>(null)
    private var notificationOpenAdminState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check & Notification Channel before UI operations
        configureAppCheck(this, intent)
        MessageNotificationHelper.ensureChannel(applicationContext)
        notificationContactIdState = intent?.getStringExtra(MessageNotificationHelper.EXTRA_CONTACT_ID)
        notificationOpenAdminState = intent?.getBooleanExtra(MessageNotificationHelper.EXTRA_OPEN_ADMIN_ALERTS, false) == true

        // STEP 2: Enable Edge-to-Edge and launch Compose UI
        enableEdgeToEdge()
        setContent {
            val appContext = applicationContext
            val prefsRepo = remember(appContext) { UserPreferencesRepository(appContext) }
            val prefs by prefsRepo.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = com.example.data.preferences.UserAppPreferences()
            )

            NomiChatTheme(themeMode = prefs.themeMode) {
                AppNavigation(
                    preferencesRepository = prefsRepo,
                    themeMode = prefs.themeMode,
                    pendingNotificationContactId = notificationContactIdState,
                    onConsumedNotificationContactId = { notificationContactIdState = null },
                    pendingOpenAdminAlerts = notificationOpenAdminState,
                    onConsumedOpenAdminAlerts = { notificationOpenAdminState = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val contactId = intent.getStringExtra(MessageNotificationHelper.EXTRA_CONTACT_ID)
        if (!contactId.isNullOrBlank()) {
            notificationContactIdState = contactId
        }
        if (intent.getBooleanExtra(MessageNotificationHelper.EXTRA_OPEN_ADMIN_ALERTS, false)) {
            notificationOpenAdminState = true
        }
    }
}

private enum class ActiveModal {
    NONE,
    EDIT_PROFILE,
    EDIT_NAME,
    EDIT_BIO,
    EDIT_USERNAME,
    EDIT_DOB,
    CHANGE_PASSWORD,
    ADD_CONTACT,
    APPEARANCE,
    PASSCODE,
    PREMIUM
}

@Composable
fun AppNavigation(
    preferencesRepository: UserPreferencesRepository,
    themeMode: ThemeMode,
    pendingNotificationContactId: String? = null,
    onConsumedNotificationContactId: () -> Unit = {},
    pendingOpenAdminAlerts: Boolean = false,
    onConsumedOpenAdminAlerts: () -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
    val networkObserver = remember(appContext) { NetworkConnectivityObserver(appContext) }
    val networkStatus by remember(networkObserver) {
        networkObserver.observe()
    }.collectAsStateWithLifecycle(initialValue = networkObserver.currentStatus())
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val prefsOrNull by remember(preferencesRepository) {
        preferencesRepository.preferencesFlow.map<UserAppPreferences, UserAppPreferences?> { it }
    }.collectAsStateWithLifecycle(initialValue = null)

    var globalPopupMessage by remember { mutableStateOf<String?>(null) }
    var suppressRootPopup by remember { mutableStateOf(false) }
    var globalAuthError by remember { mutableStateOf<String?>(null) }
    var bottomTab by remember { mutableStateOf(MainBottomTab.CHATS) }

    LaunchedEffect(globalPopupMessage) {
        if (!globalPopupMessage.isNullOrBlank()) {
            delay(3000L)
            globalPopupMessage = null
        }
    }

    val prefs = prefsOrNull
    val user = currentUser
    val activeUserId = prefs?.activeUserId.orEmpty()
    val activeUsername = prefs?.activeUsername.orEmpty()
    val hasPersistedUsernameSession = user == null && activeUserId.startsWith("usr_") && activeUsername.isNotBlank()
    var activeUsernameSession by remember(activeUserId, activeUsername) {
        mutableStateOf(
            if (hasPersistedUsernameSession) activeUserId to activeUsername else null
        )
    }

    LaunchedEffect(user, activeUserId, activeUsername) {
        if (user == null && activeUserId.startsWith("usr_") && activeUsername.isNotBlank()) {
            activeUsernameSession = activeUserId to activeUsername
            val db = NomiChatRepository.getPersistentDb(appContext)
            when (val outcome = NomiChatRepository.validateUsernameSession(db, activeUserId, activeUsername)) {
                is NomiChatRepository.UsernameAuthResult.Success -> {
                    activeUsernameSession = outcome.userId to outcome.username
                    if (outcome.username != activeUsername) {
                        preferencesRepository.saveActiveSession(outcome.userId, outcome.username)
                    }
                }
                is NomiChatRepository.UsernameAuthResult.Banned -> {
                    // Keep shell attached so BannedAccountAppealScreen renders immediately with appeal submission
                    activeUsernameSession = outcome.userId to outcome.username
                }
                is NomiChatRepository.UsernameAuthResult.Error -> {
                    activeUsernameSession = null
                    preferencesRepository.saveActiveSession("", "")
                    globalAuthError = outcome.message
                }
            }
        } else if (user == null && activeUserId.isBlank() && activeUsername.isNotBlank()) {
            // Migrate legacy local-only activeUsername session if it already exists on Firebase, else prompt login
            val db = NomiChatRepository.getPersistentDb(appContext)
            val resolved = NomiChatRepository.resolveAccountUidByUsername(db, activeUsername)
            if (resolved != null) {
                preferencesRepository.saveActiveSession(resolved.first, activeUsername)
                activeUsernameSession = resolved.first to activeUsername
            } else {
                preferencesRepository.saveActiveSession("", "")
                activeUsernameSession = null
            }
        } else {
            activeUsernameSession = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            prefs == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = EmeraldMid)
                }
            }
            user != null -> {
                val uid = user.uid
                key("cloud_$uid") {
                    val viewModel: NomiChatViewModel = viewModel(
                        key = "cloud_$uid",
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY])
                                val db = NomiChatRepository.getPersistentDb(app)
                                NomiChatViewModel(
                                    repository = NomiChatRepository(db),
                                    smartReplyRepository = SmartReplyRepository(),
                                    preferencesRepository = UserPreferencesRepository(app),
                                    currentUserId = uid,
                                    networkObserver = networkObserver
                                )
                            }
                        }
                    )
                    NomiChatMainShell(
                        viewModel = viewModel,
                        preferencesRepository = preferencesRepository,
                        currentThemeMode = themeMode,
                        bottomTab = bottomTab,
                        pendingNotificationContactId = pendingNotificationContactId,
                        onConsumedNotificationContactId = onConsumedNotificationContactId,
                        pendingOpenAdminAlerts = pendingOpenAdminAlerts,
                        onConsumedOpenAdminAlerts = onConsumedOpenAdminAlerts,
                        onSelectBottomTab = { bottomTab = it },
                        onResetToAuth = { bottomTab = MainBottomTab.CHATS },
                        onShowGlobalPopup = { message -> globalPopupMessage = message },
                        onAuthRejected = { reason -> globalAuthError = reason },
                        activeGlobalPopupMessage = globalPopupMessage,
                        onDialogShowsPopup = { suppressRootPopup = it }
                    )
                }
            }
            activeUsernameSession != null -> {
                val (sessionUid, sessionHandle) = activeUsernameSession!!
                key("username_$sessionUid") {
                    val viewModel: NomiChatViewModel = viewModel(
                        key = "username_$sessionUid",
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY])
                                val db = NomiChatRepository.getPersistentDb(app)
                                NomiChatViewModel(
                                    repository = NomiChatRepository(
                                        db = db,
                                        sessionUserId = sessionUid,
                                        sessionUsername = sessionHandle
                                    ),
                                    smartReplyRepository = SmartReplyRepository(),
                                    preferencesRepository = UserPreferencesRepository(app),
                                    currentUserId = sessionUid,
                                    networkObserver = networkObserver
                                )
                            }
                        }
                    )
                    NomiChatMainShell(
                        viewModel = viewModel,
                        preferencesRepository = preferencesRepository,
                        currentThemeMode = themeMode,
                        bottomTab = bottomTab,
                        pendingNotificationContactId = pendingNotificationContactId,
                        onConsumedNotificationContactId = onConsumedNotificationContactId,
                        pendingOpenAdminAlerts = pendingOpenAdminAlerts,
                        onConsumedOpenAdminAlerts = onConsumedOpenAdminAlerts,
                        onSelectBottomTab = { bottomTab = it },
                        onResetToAuth = {
                            activeUsernameSession = null
                            bottomTab = MainBottomTab.CHATS
                        },
                        onShowGlobalPopup = { message -> globalPopupMessage = message },
                        onAuthRejected = { reason -> globalAuthError = reason },
                        activeGlobalPopupMessage = globalPopupMessage,
                        onDialogShowsPopup = { suppressRootPopup = it }
                    )
                }
            }
            else -> {
                AuthScreen(
                    initialErrorMessage = globalAuthError,
                    initialIsLoginTab = globalAuthError != null && prefs.authFlowMode == AuthFlowMode.RETURNING_LOGIN,
                    onDismissInitialError = { globalAuthError = null },
                    onSavePreferredIdentity = { name, username, dob, mode, returningId ->
                        preferencesRepository.savePreferredSignInIdentity(
                            displayName = name,
                            username = username,
                            dob = dob,
                            authFlowMode = mode,
                            returningIdentifier = returningId
                        )
                    },
                    onDirectAuthSuccess = { directUserId, directUsername, directName, directDob, mode ->
                        preferencesRepository.savePreferredSignInIdentity(
                            displayName = directName,
                            username = directUsername,
                            dob = directDob,
                            authFlowMode = mode,
                            returningIdentifier = if (mode == AuthFlowMode.RETURNING_LOGIN) directUsername else ""
                        )
                        preferencesRepository.saveActiveSession(directUserId, directUsername)
                        activeUsernameSession = directUserId to directUsername
                        globalAuthError = null
                        bottomTab = MainBottomTab.CHATS
                    },
                    onAuthSuccess = {
                        globalAuthError = null
                        bottomTab = MainBottomTab.CHATS
                    },
                    networkStatus = networkStatus
                )
            }
        }

        SmallFloatingPopup(
            message = if (suppressRootPopup) null else globalPopupMessage,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun NomiChatMainShell(
    viewModel: NomiChatViewModel,
    preferencesRepository: UserPreferencesRepository,
    currentThemeMode: ThemeMode,
    bottomTab: MainBottomTab,
    pendingNotificationContactId: String? = null,
    onConsumedNotificationContactId: () -> Unit = {},
    pendingOpenAdminAlerts: Boolean = false,
    onConsumedOpenAdminAlerts: () -> Unit = {},
    onSelectBottomTab: (MainBottomTab) -> Unit,
    onResetToAuth: () -> Unit,
    onShowGlobalPopup: (String) -> Unit,
    onAuthRejected: (String) -> Unit,
    activeGlobalPopupMessage: String? = null,
    onDialogShowsPopup: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.setAppForeground(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setAppForeground(false)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.incomingNotificationEvents.collect { alert ->
            MessageNotificationHelper.showIncomingMessageNotification(
                context = context.applicationContext,
                contactId = alert.contactId,
                senderName = alert.senderName,
                senderHandle = alert.senderHandle,
                messageText = alert.text
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.adminNotificationEvents.collect { adminAlert ->
            if (viewModel.isAdminSession.value) {
                MessageNotificationHelper.showAdminModerationNotification(
                    context = context.applicationContext,
                    alertId = adminAlert.id,
                    reportedHandle = adminAlert.reportedUsername,
                    reporterHandle = adminAlert.reporterUsername,
                    reason = adminAlert.reason,
                    alertType = adminAlert.alertType,
                    aiRecommendation = adminAlert.aiRecommendation,
                    aiPrompt = adminAlert.aiPrompt
                )
            }
        }
    }

    LaunchedEffect(pendingNotificationContactId) {
        val targetId = pendingNotificationContactId
        if (!targetId.isNullOrBlank()) {
            viewModel.openConversation(targetId)
            onConsumedNotificationContactId()
        }
    }

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val contactsState by viewModel.filteredContactsState.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val isSearchVisible by viewModel.isSearchVisible.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val activeContact by viewModel.activeContact.collectAsStateWithLifecycle()
    val activeMessagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val isContactTyping by viewModel.isContactTyping.collectAsStateWithLifecycle()
    val quickSuggestions by viewModel.quickSuggestions.collectAsStateWithLifecycle()
    val isGeneratingSuggestions by viewModel.isGeneratingSuggestions.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val popupMessage by viewModel.popupMessage.collectAsStateWithLifecycle()
    val isBannedAccount by viewModel.isBannedAccount.collectAsStateWithLifecycle()
    val authRejectionMessage by viewModel.authRejectionMessage.collectAsStateWithLifecycle()
    val totalUnreadCount by viewModel.totalUnreadCount.collectAsStateWithLifecycle()
    val usernameSearchState by viewModel.usernameSearchState.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val activeStories by viewModel.activeStoriesState.collectAsStateWithLifecycle()
    val seenStoryIds by viewModel.seenStoryIdsState.collectAsStateWithLifecycle()
    val isPostingStory by viewModel.isPostingStory.collectAsStateWithLifecycle()
    val isUploadingBanner by viewModel.isUploadingBanner.collectAsStateWithLifecycle()
    val isUploadingPhoto by viewModel.isUploadingPhoto.collectAsStateWithLifecycle()
    val isSavingProfile by viewModel.isSavingProfile.collectAsStateWithLifecycle()
    val profileSaveError by viewModel.profileSaveError.collectAsStateWithLifecycle()
    val isChangingPassword by viewModel.isChangingPassword.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val inAppAlertBanner by viewModel.inAppAlertBanner.collectAsStateWithLifecycle()
    val isAdminSession by viewModel.isAdminSession.collectAsStateWithLifecycle()
    val moderationAlerts by viewModel.moderationAlertsState.collectAsStateWithLifecycle()
    val adminUsers by viewModel.adminUsersState.collectAsStateWithLifecycle()
    val isAdminActionLoading by viewModel.isAdminActionLoading.collectAsStateWithLifecycle()
    val pendingModerationCount by viewModel.pendingModerationCount.collectAsStateWithLifecycle()
    val inAppAdminBanner by viewModel.inAppAdminBanner.collectAsStateWithLifecycle()
    val isSubmittingBanAppeal by viewModel.isSubmittingBanAppeal.collectAsStateWithLifecycle()
    val submittedBanAppealResult by viewModel.submittedBanAppealResult.collectAsStateWithLifecycle()
    val banAppealError by viewModel.banAppealError.collectAsStateWithLifecycle()

    val rawAllThreads by viewModel.allThreadsState.collectAsStateWithLifecycle()
    val archivedChats by viewModel.archivedChatsState.collectAsStateWithLifecycle()
    val blockedContacts by viewModel.blockedContactsState.collectAsStateWithLifecycle()
    var activeModal by remember { mutableStateOf(ActiveModal.NONE) }
    var isPrivacySettingsOpen by remember { mutableStateOf(false) }
    var isArchivedFolderOpen by remember { mutableStateOf(false) }
    var isAdminPanelOpen by remember { mutableStateOf(false) }
    var isStoryEditorOpen by remember { mutableStateOf(false) }
    var pendingStoryMediaUri by remember { mutableStateOf<Uri?>(null) }
    var activeStoryAuthorKey by remember { mutableStateOf<String?>(null) }
    var avatarPreviewThreadId by remember { mutableStateOf<String?>(null) }
    var viewContactThreadId by remember { mutableStateOf<String?>(null) }
    var renameThreadId by remember { mutableStateOf<String?>(null) }
    var reportThreadId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pendingOpenAdminAlerts, isAdminSession) {
        if (pendingOpenAdminAlerts && isAdminSession) {
            isAdminPanelOpen = true
            onConsumedOpenAdminAlerts()
        } else if (!isAdminSession) {
            isAdminPanelOpen = false
        }
    }

    val modalOfflinePopup = if (
        activeModal == ActiveModal.ADD_CONTACT &&
        activeGlobalPopupMessage == NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE
    ) {
        activeGlobalPopupMessage
    } else null

    LaunchedEffect(modalOfflinePopup) {
        onDialogShowsPopup(modalOfflinePopup != null)
    }
    DisposableEffect(Unit) {
        onDispose { onDialogShowsPopup(false) }
    }

    val storyMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingStoryMediaUri = uri
            isStoryEditorOpen = true
        }
    }
    val launchStoryMediaPicker = {
        storyMediaPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    val allThreads = (contactsState as? UiState.Success)?.data.orEmpty()
    val lookupThreads = rawAllThreads.ifEmpty { allThreads }
    val activeStoryGroup = remember(activeStoryAuthorKey, activeStories) {
        val key = activeStoryAuthorKey ?: return@remember emptyList()
        activeStories.filter { it.userId.ifBlank { it.authorUsername } == key }
    }
    val avatarPreviewThread = avatarPreviewThreadId?.let { id ->
        lookupThreads.firstOrNull { it.id == id }?.takeIf { !it.isBanned }
    }
    val viewContactThread = viewContactThreadId?.let { id ->
        lookupThreads.firstOrNull { it.id == id }?.takeIf { !it.isBanned }
    }
    val renameThread = renameThreadId?.let { id -> lookupThreads.firstOrNull { it.id == id } }
    val reportThread = reportThreadId?.let { id -> lookupThreads.firstOrNull { it.id == id } }

    LaunchedEffect(lookupThreads, avatarPreviewThreadId, viewContactThreadId) {
        avatarPreviewThreadId?.let { id ->
            if (lookupThreads.firstOrNull { it.id == id }?.isBanned == true) {
                avatarPreviewThreadId = null
                onShowGlobalPopup("This account is banned.")
            }
        }
        viewContactThreadId?.let { id ->
            if (lookupThreads.firstOrNull { it.id == id }?.isBanned == true) {
                viewContactThreadId = null
                onShowGlobalPopup("This account is banned.")
            }
        }
    }

    LaunchedEffect(activeStoryAuthorKey, activeStoryGroup) {
        if (activeStoryAuthorKey != null && activeStoryGroup.isEmpty()) {
            activeStoryAuthorKey = null
        }
    }

    LaunchedEffect(inAppAlertBanner) {
        if (inAppAlertBanner != null) {
            delay(4000L)
            viewModel.dismissInAppAlertBanner()
        }
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(popupMessage) {
        val msg = popupMessage
        if (!msg.isNullOrBlank()) {
            onShowGlobalPopup(msg)
            viewModel.clearPopup()
        }
    }

    if (isBannedAccount) {
        val currentProfile = (profileState as? UiState.Success)?.data
        BannedAccountAppealScreen(
            displayName = currentProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: prefs.preferredDisplayName.ifBlank { "Account" },
            username = currentProfile?.username?.takeIf { it.isNotBlank() }
                ?: prefs.preferredUsername.ifBlank { prefs.activeUsername },
            photoData = currentProfile?.photoData,
            isSubmitting = isSubmittingBanAppeal,
            errorMessage = banAppealError,
            submittedEvaluation = submittedBanAppealResult,
            onClearError = viewModel::clearBanAppealFeedback,
            onSubmitAppeal = viewModel::submitBanAppeal,
            onSignOutClick = {
                viewModel.prepareImmediateLogout()
                PendingAuthCredentials.clear()
                onAuthRejected("Your ID is banned")
                onResetToAuth()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { preferencesRepository.clearPendingSignInFlow() }
                    runCatching { preferencesRepository.saveActiveSession("", "") }
                }
                if (Firebase.auth.currentUser != null) {
                    signOutUser(
                        credentialManager = credentialManager,
                        scope = scope
                    )
                }
            }
        )
        return
    }

    LaunchedEffect(authRejectionMessage) {
        val reason = authRejectionMessage
        if (!reason.isNullOrBlank()) {
            onAuthRejected(reason)
            viewModel.clearAuthRejection()
            onResetToAuth()
            preferencesRepository.saveActiveSession("", "")
            if (Firebase.auth.currentUser != null) {
                signOutUser(
                    credentialManager = credentialManager,
                    scope = scope
                )
            }
        }
    }

    if (prefs.isAppLockEnabled && !isAppUnlocked) {
        AppLockOverlay(onVerifyPin = { pin -> viewModel.verifyPin(pin) })
        return
    }

    val profile = (profileState as? UiState.Success)?.data

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (activeContact == null && !isPrivacySettingsOpen && !isArchivedFolderOpen) {
                    NomiBottomBar(
                        selectedTab = bottomTab,
                        totalUnreadCount = totalUnreadCount,
                        onSelectTab = { tab ->
                            isPrivacySettingsOpen = false
                            isArchivedFolderOpen = false
                            onSelectBottomTab(tab)
                            when (tab) {
                                MainBottomTab.CHATS -> viewModel.selectPillFilter(ChatPillFilter.CHATS)
                                MainBottomTab.GROUPS -> viewModel.selectPillFilter(ChatPillFilter.GROUPS)
                                MainBottomTab.CALLS -> viewModel.selectPillFilter(ChatPillFilter.CALLS)
                                MainBottomTab.SETTINGS -> Unit
                            }
                        },
                        onPlusClick = launchStoryMediaPicker
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = if (activeContact == null && !isPrivacySettingsOpen && !isArchivedFolderOpen) {
                            innerPadding.calculateBottomPadding()
                        } else {
                            0.dp
                        }
                    )
            ) {
                val currentThread = activeContact
                if (currentThread != null) {
                    val replyingToMessage by viewModel.replyingToMessage.collectAsStateWithLifecycle()
                    ConversationScreen(
                        contact = currentThread,
                        messagesState = activeMessagesState,
                        currentUserProfile = profile,
                        isContactTyping = isContactTyping,
                        smartAiEnabled = prefs.smartAiEnabled,
                        quickSuggestions = quickSuggestions,
                        isGeneratingSuggestions = isGeneratingSuggestions,
                        replyingToMessage = replyingToMessage,
                        onReplyMessage = viewModel::setReplyingToMessage,
                        onDeleteMessage = viewModel::deleteMessage,
                        onUpdateTyping = viewModel::updateTypingStatus,
                        onToggleSmartAi = viewModel::setSmartAiEnabled,
                        onRequestSuggestions = viewModel::requestQuickSuggestions,
                        onDismissSuggestions = viewModel::dismissQuickSuggestions,
                        onSendMessage = viewModel::sendMessage,
                        onSendVoiceMessage = viewModel::sendVoiceMessage,
                        onSendImageUri = { uri -> viewModel.sendImageMessageFromUri(context, uri) },
                        onSendCameraBitmap = viewModel::sendImageMessageFromBitmap,
                        onSendFileUri = { uri -> viewModel.sendFileAttachmentMessage(context, uri) },
                        onVoiceCallInitiated = viewModel::logVoiceCallAction,
                        onReportContact = viewModel::reportActiveContact,
                        onToggleBlockContact = { viewModel.toggleBlockChat(currentThread) },
                        onToggleArchiveContact = { viewModel.toggleArchiveChat(currentThread) },
                        onToggleMuteContact = { viewModel.toggleMuteChat(currentThread) },
                        onSetDisappearingMessages = { sec -> viewModel.setDisappearingMessages(currentThread, sec) },
                        onSetChatTheme = { themeKey -> viewModel.setChatTheme(currentThread, themeKey) },
                        onToggleLockChat = { viewModel.toggleLockChat(currentThread) },
                        onClearMessages = { viewModel.clearChatMessages(currentThread) },
                        onDeleteChat = { viewModel.deleteChatPermanently(currentThread) },
                        onShowNotice = onShowGlobalPopup,
                        onBack = viewModel::closeConversation,
                        networkStatus = networkStatus
                    )
                } else if (isPrivacySettingsOpen) {
                    PrivacySettingsScreen(
                        profile = profile,
                        allThreads = rawAllThreads,
                        blockedContacts = blockedContacts,
                        onSavePrivacySettings = viewModel::updatePrivacySettings,
                        onUnblockContact = viewModel::toggleBlockChat,
                        onShowNotice = onShowGlobalPopup,
                        onBack = { isPrivacySettingsOpen = false }
                    )
                } else if (isArchivedFolderOpen) {
                    ArchivedChatsScreen(
                        archivedChats = archivedChats,
                        activeStories = activeStories,
                        seenStoryIds = seenStoryIds,
                        onOpenChat = viewModel::openConversation,
                        onAvatarClick = { thread ->
                            if (thread.isBanned) {
                                onShowGlobalPopup("This account is banned.")
                                return@ArchivedChatsScreen
                            }
                            val threadStories = activeStories.filter { story ->
                                (thread.peerUid.isNotBlank() && story.userId == thread.peerUid) ||
                                    (thread.username.isNotBlank() && story.authorUsername.equals(thread.username, ignoreCase = true))
                            }
                            if (threadStories.isNotEmpty() && !thread.blocked) {
                                activeStoryAuthorKey = threadStories.first().let { it.userId.ifBlank { it.authorUsername } }
                            } else {
                                avatarPreviewThreadId = thread.id
                            }
                        },
                        onRenameChat = { thread -> renameThreadId = thread.id },
                        onViewContact = { thread ->
                            if (thread.isBanned) {
                                onShowGlobalPopup("This account is banned.")
                            } else {
                                viewContactThreadId = thread.id
                            }
                        },
                        onUnarchiveChat = viewModel::toggleArchiveChat,
                        onToggleBlockChat = viewModel::toggleBlockChat,
                        onDeleteChat = viewModel::deleteChatPermanently,
                        onBack = { isArchivedFolderOpen = false },
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refreshAllData
                    )
                } else if (bottomTab == MainBottomTab.SETTINGS) {
                    ProfileScreen(
                        profile = profile,
                        themeMode = currentThemeMode,
                        notificationsEnabled = prefs.notificationsEnabled,
                        isAppLockEnabled = prefs.isAppLockEnabled,
                        onBackToChats = {
                            onSelectBottomTab(MainBottomTab.CHATS)
                            viewModel.selectPillFilter(ChatPillFilter.CHATS)
                        },
                        onEditProfileClick = {
                            viewModel.clearProfileSaveError()
                            activeModal = ActiveModal.EDIT_PROFILE
                        },
                        onEditNameClick = {
                            viewModel.clearProfileSaveError()
                            activeModal = ActiveModal.EDIT_NAME
                        },
                        onEditBioClick = {
                            viewModel.clearProfileSaveError()
                            activeModal = ActiveModal.EDIT_BIO
                        },
                        onEditUsernameClick = {
                            viewModel.clearProfileSaveError()
                            activeModal = ActiveModal.EDIT_USERNAME
                        },
                        onEditDobClick = {
                            viewModel.clearProfileSaveError()
                            activeModal = ActiveModal.EDIT_DOB
                        },
                        onPhotoPicked = { uri -> viewModel.updateProfilePhotoFromUri(context, uri) },
                        onBannerPicked = { uri -> viewModel.updateProfileBannerFromUri(context, uri) },
                        onOpenPrivacySettings = { isPrivacySettingsOpen = true },
                        onOpenPasscodeSetup = { activeModal = ActiveModal.PASSCODE },
                        onOpenChangePassword = { activeModal = ActiveModal.CHANGE_PASSWORD },
                        onToggleNotifications = viewModel::setNotificationsEnabled,
                        onToggleBlackTheme = { enabled ->
                            viewModel.setThemeMode(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT)
                        },
                        onOpenAppearancePicker = { activeModal = ActiveModal.APPEARANCE },
                        onOpenAddContact = {
                            viewModel.resetUsernameSearch()
                            activeModal = ActiveModal.ADD_CONTACT
                        },
                        onLogoutClick = {
                            viewModel.prepareImmediateLogout()
                            PendingAuthCredentials.clear()
                            onResetToAuth()
                            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                runCatching { preferencesRepository.clearPendingSignInFlow() }
                                runCatching { preferencesRepository.saveActiveSession("", "") }
                            }
                            signOutUser(
                                credentialManager = credentialManager,
                                scope = scope
                            )
                        },
                        networkStatus = networkStatus,
                        isCloudSession = viewModel.isCloudSession,
                        onReconnectSyncClick = viewModel::reconnectCloudSync,
                        isUploadingBanner = isUploadingBanner,
                        isUploadingPhoto = isUploadingPhoto,
                        isSavingProfile = isSavingProfile,
                        isChangingPassword = isChangingPassword,
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refreshAllData,
                        isAdminSession = isAdminSession,
                        pendingModerationCount = pendingModerationCount,
                        onOpenAdminModeration = { if (isAdminSession) isAdminPanelOpen = true },
                        onConnectGoogleSyncClick = {
                            if (!networkStatus.isConnected) {
                                onShowGlobalPopup("Connect to Wi-Fi or mobile data to enable Cloud Sync.")
                            } else {
                                scope.launch {
                                    preferencesRepository.savePreferredSignInIdentity(
                                        displayName = profile?.displayName.orEmpty(),
                                        username = profile?.username.orEmpty(),
                                        dob = profile?.dob.orEmpty().ifBlank { "2000-01-01" },
                                        authFlowMode = AuthFlowMode.GOOGLE_QUICK,
                                        returningIdentifier = ""
                                    )
                                    onGoogleSignInClicked(
                                        context = context,
                                        credentialManager = credentialManager,
                                        onAuthSuccess = {
                                            onShowGlobalPopup("Cloud Sync connected!")
                                        },
                                        onAuthError = { msg ->
                                            onShowGlobalPopup(msg)
                                        },
                                        scope = scope
                                    )
                                }
                            }
                        }
                    )
                } else {
                    ChatsScreen(
                        contactsState = contactsState,
                        archivedChats = archivedChats,
                        profile = profile,
                        activeStories = activeStories,
                        seenStoryIds = seenStoryIds,
                        selectedFilter = selectedFilter,
                        isSearchVisible = isSearchVisible,
                        searchQuery = searchQuery,
                        onToggleSearch = viewModel::toggleSearch,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onSelectFilter = { filter ->
                            viewModel.selectPillFilter(filter)
                            onSelectBottomTab(
                                when (filter) {
                                    ChatPillFilter.GROUPS -> MainBottomTab.GROUPS
                                    ChatPillFilter.CALLS -> MainBottomTab.CALLS
                                    else -> MainBottomTab.CHATS
                                }
                            )
                        },
                        onOpenProfile = { onSelectBottomTab(MainBottomTab.SETTINGS) },
                        onOpenPrivacySettings = { isPrivacySettingsOpen = true },
                        onOpenArchivedFolder = { isArchivedFolderOpen = true },
                        onOpenPremium = { activeModal = ActiveModal.PREMIUM },
                        onOpenChat = viewModel::openConversation,
                        onAvatarClick = { thread ->
                            if (thread.isBanned) {
                                onShowGlobalPopup("This account is banned.")
                                return@ChatsScreen
                            }
                            val threadStories = activeStories.filter { story ->
                                (thread.peerUid.isNotBlank() && story.userId == thread.peerUid) ||
                                    (thread.username.isNotBlank() && story.authorUsername.equals(thread.username, ignoreCase = true))
                            }
                            if (threadStories.isNotEmpty() && !thread.blocked) {
                                activeStoryAuthorKey = threadStories.first().let { it.userId.ifBlank { it.authorUsername } }
                            } else {
                                avatarPreviewThreadId = thread.id
                            }
                        },
                        onRenameChat = { thread -> renameThreadId = thread.id },
                        onViewContact = { thread ->
                            if (thread.isBanned) {
                                onShowGlobalPopup("This account is banned.")
                            } else {
                                viewContactThreadId = thread.id
                            }
                        },
                        onToggleLockChat = viewModel::toggleLockChat,
                        onToggleBlockChat = viewModel::toggleBlockChat,
                        onToggleArchiveChat = viewModel::toggleArchiveChat,
                        onDeleteChat = viewModel::deleteChatPermanently,
                        onAddContactClick = {
                            viewModel.resetUsernameSearch()
                            activeModal = ActiveModal.ADD_CONTACT
                        },
                        onPostStoryClick = launchStoryMediaPicker,
                        onOpenStoryGroup = { group ->
                            activeStoryAuthorKey = group.firstOrNull()?.let { it.userId.ifBlank { it.authorUsername } }
                        },
                        networkStatus = networkStatus,
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refreshAllData,
                        isAdminSession = isAdminSession,
                        pendingModerationCount = pendingModerationCount,
                        onOpenAdminModeration = { if (isAdminSession) isAdminPanelOpen = true }
                    )
                }
            }
        }

        if (isStoryEditorOpen) {
            StoryEditorScreen(
                mediaUri = pendingStoryMediaUri,
                contacts = lookupThreads,
                isPosting = isPostingStory,
                onPickMediaClick = launchStoryMediaPicker,
                onClose = {
                    isStoryEditorOpen = false
                    pendingStoryMediaUri = null
                },
                onPostStory = { uri, caption, storyFont, musicTag, overlayText, overlayColorHex, overlayYFraction, stickersJson, drawingJson, cropMode, rotationDegrees ->
                    viewModel.postRichStory(
                        context = context,
                        uri = uri,
                        caption = caption,
                        storyFont = storyFont,
                        musicTag = musicTag,
                        overlayText = overlayText,
                        overlayColorHex = overlayColorHex,
                        overlayYFraction = overlayYFraction,
                        stickersJson = stickersJson,
                        drawingJson = drawingJson,
                        cropMode = cropMode,
                        rotationDegrees = rotationDegrees,
                        onSuccess = {
                            isStoryEditorOpen = false
                            pendingStoryMediaUri = null
                        }
                    )
                }
            )
        } else if (activeStoryAuthorKey != null && activeStoryGroup.isNotEmpty()) {
            StoryViewerScreen(
                stories = activeStoryGroup,
                currentUserId = profile?.userId.orEmpty(),
                observeStoryViews = viewModel::observeStoryViews,
                onStoryViewed = viewModel::recordStoryView,
                onToggleLike = viewModel::toggleStoryLike,
                onSendReaction = viewModel::sendStoryReaction,
                onSendReply = viewModel::replyToStory,
                onDeleteStory = viewModel::deleteStory,
                onDismiss = { activeStoryAuthorKey = null }
            )
        }

        if (isAdminSession && isAdminPanelOpen) {
            AdminModerationScreen(
                alerts = moderationAlerts,
                users = adminUsers,
                isLoading = isAdminActionLoading,
                onDismiss = { isAdminPanelOpen = false },
                onIgnore = viewModel::ignoreModerationAlert,
                onBanAlert = viewModel::banUserFromAlert,
                onRecoveryAlert = viewModel::recoverUserFromAlert,
                onVerifyUser = viewModel::adminVerifyUser,
                onBanUser = viewModel::adminBanUser,
                onUnbanUser = viewModel::adminUnbanUser
            )
        }

        AnimatedVisibility(
            visible = (isAdminSession && inAppAdminBanner != null && !isAdminPanelOpen) || inAppAlertBanner != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 560.dp)
        ) {
            val adminAlert = if (isAdminSession && !isAdminPanelOpen) inAppAdminBanner else null
            if (adminAlert != null) {
                val reportedPhotoData = remember(adminAlert, adminUsers) {
                    adminUsers.firstOrNull {
                        (adminAlert.reportedUid.isNotBlank() && it.uid == adminAlert.reportedUid) ||
                            (adminAlert.reportedUsername.isNotBlank() && it.username.equals(adminAlert.reportedUsername, ignoreCase = true))
                    }?.photoData
                }
                InAppAdminAlertBanner(
                    alert = adminAlert,
                    reportedPhotoData = reportedPhotoData,
                    onOpenAdminPanel = {
                        viewModel.dismissInAppAdminBanner()
                        isAdminPanelOpen = true
                    },
                    onIgnore = { viewModel.ignoreModerationAlert(adminAlert.id) },
                    onBan = { viewModel.banUserFromAlert(adminAlert) },
                    onRecovery = { viewModel.recoverUserFromAlert(adminAlert) },
                    onDismiss = viewModel::dismissInAppAdminBanner
                )
            } else {
                inAppAlertBanner?.let { alert ->
                    InAppMessageBanner(
                        alert = alert,
                        onClick = {
                            viewModel.dismissInAppAlertBanner()
                            viewModel.openConversation(alert.contactId)
                        },
                        onDismiss = viewModel::dismissInAppAlertBanner
                    )
                }
            }
        }
    }

    when (activeModal) {
        ActiveModal.NONE -> Unit
        ActiveModal.EDIT_PROFILE -> EditProfileDialog(
            initialDisplayName = profile?.displayName.orEmpty(),
            initialBio = profile?.bio.orEmpty(),
            initialUsername = profile?.username.orEmpty(),
            initialDob = profile?.dob.orEmpty().ifBlank { "2000-01-01" },
            isSaving = isSavingProfile,
            errorText = profileSaveError,
            onClearError = viewModel::clearProfileSaveError,
            onDismiss = {
                viewModel.clearProfileSaveError()
                activeModal = ActiveModal.NONE
            },
            onSave = { name, bio, username, dob ->
                viewModel.updateProfileField(
                    displayName = name,
                    username = username,
                    bio = bio,
                    dob = dob,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.EDIT_NAME -> EditSingleFieldDialog(
            title = "Edit Profile Name",
            label = "Display Name",
            initialValue = profile?.displayName.orEmpty(),
            maxLength = 60,
            isSaving = isSavingProfile,
            errorText = profileSaveError,
            onClearError = viewModel::clearProfileSaveError,
            onDismiss = {
                viewModel.clearProfileSaveError()
                activeModal = ActiveModal.NONE
            },
            onSave = { newName ->
                viewModel.updateProfileField(
                    displayName = newName,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.EDIT_BIO -> EditSingleFieldDialog(
            title = "Edit Bio",
            label = "Bio",
            initialValue = profile?.bio.orEmpty(),
            maxLength = 200,
            isSaving = isSavingProfile,
            errorText = profileSaveError,
            onClearError = viewModel::clearProfileSaveError,
            onDismiss = {
                viewModel.clearProfileSaveError()
                activeModal = ActiveModal.NONE
            },
            onSave = { newBio ->
                viewModel.updateProfileField(
                    bio = newBio,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.EDIT_USERNAME -> EditSingleFieldDialog(
            title = "Edit Username",
            label = "Username",
            initialValue = profile?.username.orEmpty(),
            maxLength = 40,
            sanitizeUsername = true,
            isSaving = isSavingProfile,
            errorText = profileSaveError,
            onClearError = viewModel::clearProfileSaveError,
            onDismiss = {
                viewModel.clearProfileSaveError()
                activeModal = ActiveModal.NONE
            },
            onSave = { newUsername ->
                viewModel.updateProfileField(
                    username = newUsername,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.EDIT_DOB -> EditSingleFieldDialog(
            title = "Edit Date of Birth",
            label = "Date of Birth (YYYY-MM-DD)",
            initialValue = profile?.dob.orEmpty().ifBlank { "2000-01-01" },
            maxLength = 20,
            isSaving = isSavingProfile,
            errorText = profileSaveError,
            onClearError = viewModel::clearProfileSaveError,
            onDismiss = {
                viewModel.clearProfileSaveError()
                activeModal = ActiveModal.NONE
            },
            onSave = { newDob ->
                viewModel.updateProfileField(
                    dob = newDob,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.CHANGE_PASSWORD -> ChangePasswordDialog(
            requireCurrentPassword = profile?.provider == "username" || !viewModel.isCloudSession,
            isChanging = isChangingPassword,
            onDismiss = { activeModal = ActiveModal.NONE },
            onSubmit = { currentPw, newPw ->
                viewModel.changePassword(
                    currentPassword = currentPw,
                    newPassword = newPw,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.ADD_CONTACT -> AddContactDialog(
            usernameSearchState = usernameSearchState,
            offlinePopupMessage = modalOfflinePopup,
            onSearchUsername = viewModel::findUserByUsername,
            onResetSearch = viewModel::resetUsernameSearch,
            onDismiss = { activeModal = ActiveModal.NONE },
            onAddDirectChat = { username ->
                viewModel.addDirectChatByUsername(
                    username = username,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            },
            onAddGroup = { name, username ->
                viewModel.addGroupChat(
                    name = name,
                    username = username,
                    onSuccess = { activeModal = ActiveModal.NONE }
                )
            }
        )
        ActiveModal.APPEARANCE -> AppearanceDialog(
            currentMode = currentThemeMode,
            onSelectMode = viewModel::setThemeMode,
            onDismiss = { activeModal = ActiveModal.NONE }
        )
        ActiveModal.PASSCODE -> AppLockSetupDialog(
            isCurrentlyEnabled = prefs.isAppLockEnabled,
            onDismiss = { activeModal = ActiveModal.NONE },
            onSavePin = viewModel::setAppLockPin
        )
        ActiveModal.PREMIUM -> PremiumFeaturesDialog(
            smartAiEnabled = prefs.smartAiEnabled,
            onToggleSmartAi = viewModel::setSmartAiEnabled,
            onDismiss = { activeModal = ActiveModal.NONE }
        )
    }

    avatarPreviewThread?.let { thread ->
        val threadStories = remember(activeStories, thread.peerUid, thread.username) {
            activeStories.filter { story ->
                (thread.peerUid.isNotBlank() && story.userId == thread.peerUid) ||
                    (thread.username.isNotBlank() && story.authorUsername.equals(thread.username, ignoreCase = true))
            }
        }
        val threadStorySeen = threadStories.isNotEmpty() && threadStories.all { it.id in seenStoryIds }
        AvatarPreviewDialog(
            thread = thread,
            hasActiveStory = threadStories.isNotEmpty(),
            storyRingSeen = threadStorySeen,
            onDismiss = { avatarPreviewThreadId = null },
            onOpenChat = {
                avatarPreviewThreadId = null
                viewModel.openConversation(thread.id)
            },
            onViewContact = {
                avatarPreviewThreadId = null
                viewContactThreadId = thread.id
            },
            onViewStory = {
                avatarPreviewThreadId = null
                activeStoryAuthorKey = threadStories.firstOrNull()?.let { it.userId.ifBlank { it.authorUsername } }
            },
            onToggleBlock = {
                avatarPreviewThreadId = null
                viewModel.toggleBlockChat(thread)
            }
        )
    }

    viewContactThread?.let { thread ->
        ContactProfileDialog(
            contact = thread,
            onDismiss = { viewContactThreadId = null },
            onToggleBlock = {
                viewContactThreadId = null
                viewModel.toggleBlockChat(thread)
            },
            onReportClick = {
                viewContactThreadId = null
                reportThreadId = thread.id
            }
        )
    }

    reportThread?.let { thread ->
        ReportAccountDialog(
            contact = thread,
            onDismiss = { reportThreadId = null },
            onConfirmReport = { reason ->
                reportThreadId = null
                viewModel.reportContact(thread, reason)
            }
        )
    }

    renameThread?.let { thread ->
        EditSingleFieldDialog(
            title = "Rename Chat",
            label = "Chat Name",
            initialValue = thread.name,
            maxLength = 60,
            onDismiss = { renameThreadId = null },
            onSave = { newName ->
                viewModel.renameChat(thread, newName)
                renameThreadId = null
            }
        )
    }
}

@Composable
private fun InAppMessageBanner(
    alert: IncomingMessageAlert,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("in_app_message_banner"),
        shape = RoundedCornerShape(18.dp),
        color = EmeraldDeep,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(EmeraldDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = "New message",
                    tint = MintOnlineGlow,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (alert.senderHandle.isNotBlank()) {
                        "${alert.senderName} (@${alert.senderHandle})"
                    } else {
                        alert.senderName
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = alert.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MintLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_in_app_banner")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss notification",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

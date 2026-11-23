package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
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
import com.example.data.model.MainBottomTab
import com.example.data.model.ThemeMode
import com.example.data.model.UiState
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.preferences.UserAppPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.NomiChatRepository
import com.example.firebase.configureAppCheck
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.onGoogleSignInClicked
import com.example.ui.auth.signOutUser
import com.example.ui.components.NomiBottomBar
import com.example.ui.components.SmallFloatingPopup
import com.example.ui.screens.AddContactDialog
import com.example.ui.screens.AppLockOverlay
import com.example.ui.screens.AppLockSetupDialog
import com.example.ui.screens.AppearanceDialog
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ConversationScreen
import com.example.ui.screens.EditSingleFieldDialog
import com.example.ui.screens.PremiumFeaturesDialog
import com.example.ui.screens.ProfileScreen
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.NomiChatTheme
import com.example.ui.viewmodel.NomiChatViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        // STEP 2: Enable Edge-to-Edge and launch Compose UI
        enableEdgeToEdge()
        setContent {
            val appContext = applicationContext
            val prefsRepo = remember(appContext) { UserPreferencesRepository(appContext) }
            val prefs by prefsRepo.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = com.example.data.preferences.UserAppPreferences()
            )

            NomiChatTheme(themeMode = prefs.themeMode) {
                AppNavigation(preferencesRepository = prefsRepo, themeMode = prefs.themeMode)
            }
        }
    }
}

private enum class ActiveModal {
    NONE,
    EDIT_NAME,
    EDIT_BIO,
    EDIT_USERNAME,
    EDIT_DOB,
    ADD_CONTACT,
    APPEARANCE,
    PASSCODE,
    PREMIUM
}

@Composable
fun AppNavigation(
    preferencesRepository: UserPreferencesRepository,
    themeMode: ThemeMode
) {
    val appContext = LocalContext.current.applicationContext
    val localAuthStore = remember(appContext) { LocalAuthStore(appContext) }
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
    var globalAuthError by remember { mutableStateOf<String?>(null) }
    var bottomTab by remember { mutableStateOf(MainBottomTab.CHATS) }
    var validatedLocalUser by remember { mutableStateOf<String?>(null) }
    var lastValidatedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(globalPopupMessage) {
        if (!globalPopupMessage.isNullOrBlank()) {
            delay(3000L)
            globalPopupMessage = null
        }
    }

    val prefs = prefsOrNull
    val user = currentUser
    val activeUsernameRaw = prefs?.activeUsername?.trim().orEmpty()

    LaunchedEffect(prefs, user, activeUsernameRaw) {
        if (prefs == null || user != null) {
            validatedLocalUser = ""
            lastValidatedKey = ""
            return@LaunchedEffect
        }
        if (activeUsernameRaw.isEmpty()) {
            validatedLocalUser = ""
            lastValidatedKey = ""
            return@LaunchedEffect
        }
        if (lastValidatedKey == activeUsernameRaw && validatedLocalUser != null) {
            return@LaunchedEffect
        }
        when (val outcome = localAuthStore.validateActiveSession(activeUsernameRaw)) {
            is LocalAuthOutcome.Success -> {
                localAuthStore.getAccountSettings(outcome.username)?.let { saved ->
                    if (saved.themeMode != prefs.themeMode ||
                        saved.notificationsEnabled != prefs.notificationsEnabled ||
                        saved.smartAiEnabled != prefs.smartAiEnabled ||
                        saved.appLockPinHash != prefs.appLockPinHash
                    ) {
                        preferencesRepository.applyAccountSettings(
                            themeMode = saved.themeMode,
                            notificationsEnabled = saved.notificationsEnabled,
                            smartAiEnabled = saved.smartAiEnabled,
                            appLockPinHash = saved.appLockPinHash
                        )
                    }
                }
                validatedLocalUser = outcome.username
                lastValidatedKey = activeUsernameRaw
            }
            is LocalAuthOutcome.Banned -> {
                globalPopupMessage = outcome.message
                globalAuthError = outcome.message
                preferencesRepository.saveActiveUsername("")
                validatedLocalUser = ""
                lastValidatedKey = ""
            }
            is LocalAuthOutcome.Error -> {
                preferencesRepository.saveActiveUsername("")
                validatedLocalUser = ""
                lastValidatedKey = ""
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            prefs == null || (user == null && activeUsernameRaw.isNotEmpty() && lastValidatedKey != activeUsernameRaw) -> {
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
                                val databaseId = app.getString(R.string.firestore_database_id)
                                val db = FirebaseFirestore.getInstance(databaseId)
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
                        onSelectBottomTab = { bottomTab = it },
                        onResetToAuth = { bottomTab = MainBottomTab.CHATS },
                        onShowGlobalPopup = { message -> globalPopupMessage = message },
                        onAuthRejected = { reason -> globalAuthError = reason }
                    )
                }
            }
            !validatedLocalUser.isNullOrEmpty() -> {
                val localUser = validatedLocalUser.orEmpty()
                key("local_$localUser") {
                    val viewModel: NomiChatViewModel = viewModel(
                        key = "local_$localUser",
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY])
                                NomiChatViewModel(
                                    repository = LocalNomiRepository(localAuthStore),
                                    smartReplyRepository = SmartReplyRepository(),
                                    preferencesRepository = UserPreferencesRepository(app),
                                    currentUserId = localUser,
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
                        onSelectBottomTab = { bottomTab = it },
                        onResetToAuth = { bottomTab = MainBottomTab.CHATS },
                        onShowGlobalPopup = { message -> globalPopupMessage = message },
                        onAuthRejected = { reason -> globalAuthError = reason }
                    )
                }
            }
            else -> {
                AuthScreen(
                    localAuthStore = localAuthStore,
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
                    onLocalAuthSuccess = { loggedInUsername ->
                        globalAuthError = null
                        bottomTab = MainBottomTab.CHATS
                        localAuthStore.getAccountSettings(loggedInUsername)?.let { saved ->
                            preferencesRepository.applyAccountSettings(
                                themeMode = saved.themeMode,
                                notificationsEnabled = saved.notificationsEnabled,
                                smartAiEnabled = saved.smartAiEnabled,
                                appLockPinHash = saved.appLockPinHash
                            )
                        }
                        validatedLocalUser = loggedInUsername
                        lastValidatedKey = loggedInUsername
                        preferencesRepository.saveActiveUsername(loggedInUsername)
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
            message = globalPopupMessage,
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
    onSelectBottomTab: (MainBottomTab) -> Unit,
    onResetToAuth: () -> Unit,
    onShowGlobalPopup: (String) -> Unit,
    onAuthRejected: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

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

    var activeModal by remember { mutableStateOf(ActiveModal.NONE) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    LaunchedEffect(isBannedAccount) {
        if (isBannedAccount) {
            onShowGlobalPopup("Your account is banned.")
            onResetToAuth()
            preferencesRepository.saveActiveUsername("")
            if (Firebase.auth.currentUser != null) {
                signOutUser(
                    credentialManager = credentialManager,
                    scope = scope
                )
            }
        }
    }

    LaunchedEffect(authRejectionMessage) {
        val reason = authRejectionMessage
        if (!reason.isNullOrBlank()) {
            onAuthRejected(reason)
            viewModel.clearAuthRejection()
            onResetToAuth()
            preferencesRepository.saveActiveUsername("")
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
                if (activeContact == null) {
                    NomiBottomBar(
                        selectedTab = bottomTab,
                        totalUnreadCount = totalUnreadCount,
                        onSelectTab = { tab ->
                            onSelectBottomTab(tab)
                            when (tab) {
                                MainBottomTab.CHATS -> viewModel.selectPillFilter(ChatPillFilter.CHATS)
                                MainBottomTab.GROUPS -> viewModel.selectPillFilter(ChatPillFilter.GROUPS)
                                MainBottomTab.CALLS -> viewModel.selectPillFilter(ChatPillFilter.CALLS)
                                MainBottomTab.SETTINGS -> Unit
                            }
                        },
                        onPlusClick = {
                            viewModel.resetUsernameSearch()
                            activeModal = ActiveModal.ADD_CONTACT
                        }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (activeContact == null) innerPadding.calculateBottomPadding() else 0.dp)
            ) {
                val currentThread = activeContact
                if (currentThread != null) {
                    ConversationScreen(
                        contact = currentThread,
                        messagesState = activeMessagesState,
                        isContactTyping = isContactTyping,
                        smartAiEnabled = prefs.smartAiEnabled,
                        quickSuggestions = quickSuggestions,
                        isGeneratingSuggestions = isGeneratingSuggestions,
                        onToggleSmartAi = viewModel::setSmartAiEnabled,
                        onRequestSuggestions = viewModel::requestQuickSuggestions,
                        onDismissSuggestions = viewModel::dismissQuickSuggestions,
                        onSendMessage = viewModel::sendMessage,
                        onReportContact = viewModel::reportActiveContact,
                        onBack = viewModel::closeConversation
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
                        onEditNameClick = { activeModal = ActiveModal.EDIT_NAME },
                        onEditBioClick = { activeModal = ActiveModal.EDIT_BIO },
                        onEditUsernameClick = { activeModal = ActiveModal.EDIT_USERNAME },
                        onEditDobClick = { activeModal = ActiveModal.EDIT_DOB },
                        onPhotoPicked = { uri -> viewModel.updateProfilePhotoFromUri(context, uri) },
                        onOpenPasscodeSetup = { activeModal = ActiveModal.PASSCODE },
                        onToggleNotifications = viewModel::setNotificationsEnabled,
                        onOpenAppearancePicker = { activeModal = ActiveModal.APPEARANCE },
                        onOpenAddContact = { activeModal = ActiveModal.ADD_CONTACT },
                        onLogoutClick = {
                            scope.launch {
                                onResetToAuth()
                                preferencesRepository.saveActiveUsername("")
                                if (Firebase.auth.currentUser != null) {
                                    signOutUser(
                                        credentialManager = credentialManager,
                                        scope = scope
                                    )
                                }
                            }
                        },
                        networkStatus = networkStatus,
                        isCloudSession = viewModel.isCloudSession,
                        onReconnectSyncClick = viewModel::reconnectCloudSync,
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
                        onOpenPremium = { activeModal = ActiveModal.PREMIUM },
                        onOpenChat = viewModel::openConversation,
                        onToggleLockChat = viewModel::toggleLockChat,
                        onToggleBlockChat = viewModel::toggleBlockChat,
                        onDeleteChat = viewModel::deleteChatPermanently,
                        onAddContactClick = { activeModal = ActiveModal.ADD_CONTACT },
                        networkStatus = networkStatus
                    )
                }
            }
        }
    }

    when (activeModal) {
        ActiveModal.NONE -> Unit
        ActiveModal.EDIT_NAME -> EditSingleFieldDialog(
            title = "Edit Profile Name",
            label = "Display Name",
            initialValue = profile?.displayName.orEmpty(),
            maxLength = 60,
            onDismiss = { activeModal = ActiveModal.NONE },
            onSave = { newName ->
                viewModel.updateProfileField(displayName = newName)
                activeModal = ActiveModal.NONE
            }
        )
        ActiveModal.EDIT_BIO -> EditSingleFieldDialog(
            title = "Edit Bio",
            label = "Bio",
            initialValue = profile?.bio.orEmpty(),
            maxLength = 200,
            onDismiss = { activeModal = ActiveModal.NONE },
            onSave = { newBio ->
                viewModel.updateProfileField(bio = newBio)
                activeModal = ActiveModal.NONE
            }
        )
        ActiveModal.EDIT_USERNAME -> EditSingleFieldDialog(
            title = "Edit Username",
            label = "Username",
            initialValue = profile?.username.orEmpty(),
            maxLength = 40,
            sanitizeUsername = true,
            onDismiss = { activeModal = ActiveModal.NONE },
            onSave = { newUsername ->
                viewModel.updateProfileField(username = newUsername)
                activeModal = ActiveModal.NONE
            }
        )
        ActiveModal.EDIT_DOB -> EditSingleFieldDialog(
            title = "Edit Date of Birth",
            label = "Date of Birth (YYYY-MM-DD)",
            initialValue = profile?.dob.orEmpty().ifBlank { "2000-01-01" },
            maxLength = 20,
            onDismiss = { activeModal = ActiveModal.NONE },
            onSave = { newDob ->
                viewModel.updateProfileField(dob = newDob)
                activeModal = ActiveModal.NONE
            }
        )
        ActiveModal.ADD_CONTACT -> AddContactDialog(
            usernameSearchState = usernameSearchState,
            onSearchUsername = viewModel::findUserByUsername,
            onResetSearch = viewModel::resetUsernameSearch,
            onDismiss = { activeModal = ActiveModal.NONE },
            onAddContact = { name, phone, username, isGroup, peerUid, isRegisteredPeer ->
                viewModel.addNewContact(name, phone, username, isGroup, peerUid, isRegisteredPeer)
                activeModal = ActiveModal.NONE
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
}

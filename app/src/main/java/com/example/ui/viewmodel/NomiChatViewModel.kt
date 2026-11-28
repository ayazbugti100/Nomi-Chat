package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.SmartReplyRepository
import com.example.data.model.ADMIN_GMAIL
import com.example.data.model.AdminUserRecord
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPillFilter
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.ModerationAlert
import com.example.data.model.ProfileVisibilityMode
import com.example.data.model.StoryFontStyle
import com.example.data.model.StoryItem
import com.example.data.model.StoryViewRecord
import com.example.data.model.ThemeMode
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.UserSetupOutcome
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.network.NetworkStatus
import com.example.data.preferences.UserAppPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.NomiDataSource
import com.example.data.security.PendingAuthCredentials
import com.example.ui.auth.authStateFlow
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

sealed interface UsernameSearchState {
    data object Idle : UsernameSearchState
    data object Loading : UsernameSearchState
    data class Found(val result: DirectoryLookupResult) : UsernameSearchState
    data class NotFound(val message: String = "Account Not Found") : UsernameSearchState
}

@OptIn(ExperimentalCoroutinesApi::class)
class NomiChatViewModel(
    private val repository: NomiDataSource,
    private val smartReplyRepository: SmartReplyRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val currentUserId: String,
    private val networkObserver: NetworkConnectivityObserver? = null
) : ViewModel() {

    val isCloudSession: Boolean = repository.requiresInitialCloudSetup

    val networkStatus: StateFlow<NetworkStatus> = (
        networkObserver?.observe()
            ?: flowOf(NetworkStatus(isConnected = true, transportLabel = "Online"))
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = networkObserver?.currentStatus()
                ?: NetworkStatus(isConnected = true, transportLabel = "Online")
        )

    private var incomingSyncJob: Job? = null
    private var presenceHeartbeatJob: Job? = null
    private var isAppInForeground: Boolean = false

    private val _incomingNotificationEvents = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 24)
    val incomingNotificationEvents: Flow<IncomingMessageAlert> = _incomingNotificationEvents.asSharedFlow()

    val isAdminSession: StateFlow<Boolean> = Firebase.auth.authStateFlow()
        .map { user ->
            repository.requiresInitialCloudSetup &&
                user != null &&
                user.isEmailVerified &&
                user.email.equals(ADMIN_GMAIL, ignoreCase = true)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = run {
                val user = Firebase.auth.currentUser
                repository.requiresInitialCloudSetup &&
                    user != null &&
                    user.isEmailVerified &&
                    user.email.equals(ADMIN_GMAIL, ignoreCase = true)
            }
        )

    private val _adminNotificationEvents = MutableSharedFlow<ModerationAlert>(extraBufferCapacity = 16)
    val adminNotificationEvents: Flow<ModerationAlert> = _adminNotificationEvents.asSharedFlow()

    private val _inAppAdminBanner = MutableStateFlow<ModerationAlert?>(null)
    val inAppAdminBanner: StateFlow<ModerationAlert?> = _inAppAdminBanner.asStateFlow()

    val moderationAlertsState: StateFlow<List<ModerationAlert>> = isAdminSession
        .flatMapLatest { isAdmin ->
            if (isAdmin) {
                repository.observeModerationAlerts()
                    .catch { err ->
                        if (err is CancellationException) throw err
                        Log.w(TAG, "Error observing moderation alerts", err)
                        emit(emptyList())
                    }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val pendingModerationCount: StateFlow<Int> = moderationAlertsState
        .map { list -> list.count { it.status == "pending" } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = 0
        )

    val adminUsersState: StateFlow<List<AdminUserRecord>> = isAdminSession
        .flatMapLatest { isAdmin ->
            if (isAdmin) {
                repository.observeAdminUsers()
                    .catch { err ->
                        if (err is CancellationException) throw err
                        Log.w(TAG, "Error observing admin users", err)
                        emit(emptyList())
                    }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    private val _isAdminActionLoading = MutableStateFlow(false)
    val isAdminActionLoading: StateFlow<Boolean> = _isAdminActionLoading.asStateFlow()

    private val seenAdminAlertIds = mutableSetOf<String>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchVisible = MutableStateFlow(false)
    val isSearchVisible: StateFlow<Boolean> = _isSearchVisible.asStateFlow()

    private val _selectedFilter = MutableStateFlow(ChatPillFilter.CHATS)
    val selectedFilter: StateFlow<ChatPillFilter> = _selectedFilter.asStateFlow()

    private val _activeContactId = MutableStateFlow<String?>(null)
    val activeContactId: StateFlow<String?> = _activeContactId.asStateFlow()

    private val _isContactTyping = MutableStateFlow(false)
    val isContactTyping: StateFlow<Boolean> = _isContactTyping.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<ChatMessage?>(null)
    val replyingToMessage: StateFlow<ChatMessage?> = _replyingToMessage.asStateFlow()

    private val _quickSuggestions = MutableStateFlow<List<String>>(emptyList())
    val quickSuggestions: StateFlow<List<String>> = _quickSuggestions.asStateFlow()

    private val _isGeneratingSuggestions = MutableStateFlow(false)
    val isGeneratingSuggestions: StateFlow<Boolean> = _isGeneratingSuggestions.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _popupMessage = MutableStateFlow<String?>(null)
    val popupMessage: StateFlow<String?> = _popupMessage.asStateFlow()

    private val _isBannedAccount = MutableStateFlow(false)
    val isBannedAccount: StateFlow<Boolean> = _isBannedAccount.asStateFlow()

    private val _authRejectionMessage = MutableStateFlow<String?>(null)
    val authRejectionMessage: StateFlow<String?> = _authRejectionMessage.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _usernameSearchState = MutableStateFlow<UsernameSearchState>(UsernameSearchState.Idle)
    val usernameSearchState: StateFlow<UsernameSearchState> = _usernameSearchState.asStateFlow()

    private val _isPostingStory = MutableStateFlow(false)
    val isPostingStory: StateFlow<Boolean> = _isPostingStory.asStateFlow()

    private val _isUploadingBanner = MutableStateFlow(false)
    val isUploadingBanner: StateFlow<Boolean> = _isUploadingBanner.asStateFlow()

    private val _isUploadingPhoto = MutableStateFlow(false)
    val isUploadingPhoto: StateFlow<Boolean> = _isUploadingPhoto.asStateFlow()

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile.asStateFlow()

    private val _profileSaveError = MutableStateFlow<String?>(null)
    val profileSaveError: StateFlow<String?> = _profileSaveError.asStateFlow()

    private val _isChangingPassword = MutableStateFlow(false)
    val isChangingPassword: StateFlow<Boolean> = _isChangingPassword.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _localSeenStoryIds = MutableStateFlow<Set<String>>(emptySet())

    private val _inAppAlertBanner = MutableStateFlow<IncomingMessageAlert?>(null)
    val inAppAlertBanner: StateFlow<IncomingMessageAlert?> = _inAppAlertBanner.asStateFlow()

    private val storyNowTickMs = MutableStateFlow(System.currentTimeMillis())

    private var lastSuggestedMessageId: String? = null

    val preferences: StateFlow<UserAppPreferences> = preferencesRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UserAppPreferences()
        )

    val profileState: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { profile ->
            if (profile?.accountStatus == "banned" || profile?.bannedByAdmin == true) {
                _isBannedAccount.value = true
                _popupMessage.value = "Your ID is banned"
            } else if (profile != null) {
                _isBannedAccount.value = false
            }
            UiState.Success(profile)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing profile", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    private val allContactsState: StateFlow<UiState<List<ChatThread>>> = repository.observeContacts(currentUserId)
        .map<List<ChatThread>, UiState<List<ChatThread>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing contacts", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load chats"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val filteredContactsState: StateFlow<UiState<List<ChatThread>>> = combine(
        allContactsState,
        _searchQuery,
        _selectedFilter
    ) { state, query, filter ->
        when (state) {
            is UiState.Loading -> UiState.Loading
            is UiState.Error -> state
            is UiState.Success -> {
                val q = query.trim().lowercase()
                val filtered = state.data.filter { thread ->
                    val matchesInbox = !thread.archived
                    val matchesTab = when (filter) {
                        ChatPillFilter.GROUPS -> thread.threadType == "group"
                        else -> true
                    }
                    val matchesQuery = q.isEmpty() ||
                        thread.name.lowercase().contains(q) ||
                        thread.username.lowercase().contains(q) ||
                        thread.phone.lowercase().contains(q)
                    matchesInbox && matchesTab && matchesQuery
                }
                val ranked = filtered.sortedWith(
                    compareByDescending<ChatThread> { it.pinned }
                        .thenByDescending { it.unreadCount > 0L }
                        .thenByDescending { it.updatedAt?.seconds ?: 0L }
                )
                UiState.Success(ranked)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading
    )

    val allThreadsState: StateFlow<List<ChatThread>> = allContactsState
        .map { state -> (state as? UiState.Success)?.data.orEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val archivedChatsState: StateFlow<List<ChatThread>> = allContactsState
        .map { state ->
            (state as? UiState.Success)?.data.orEmpty()
                .filter { it.archived }
                .sortedWith(
                    compareByDescending<ChatThread> { it.pinned }
                        .thenByDescending { it.unreadCount > 0L }
                        .thenByDescending { it.updatedAt?.seconds ?: 0L }
                )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val blockedContactsState: StateFlow<List<ChatThread>> = allContactsState
        .map { state ->
            (state as? UiState.Success)?.data.orEmpty().filter { it.blocked }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val totalUnreadCount: StateFlow<Int> = allContactsState
        .map { state ->
            when (state) {
                is UiState.Success -> state.data
                    .filter { !it.blocked && !it.archived && !it.isBanned }
                    .sumOf { it.unreadCount.coerceAtLeast(0L) }
                    .toInt()
                else -> 0
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = 0
        )

    val activeContact: StateFlow<ChatThread?> = combine(
        allContactsState,
        _activeContactId
    ) { state, activeId ->
        if (state is UiState.Success && activeId != null) {
            state.data.firstOrNull { it.id == activeId }?.takeIf { !it.isBanned }
        } else null
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )

    val activeMessagesState: StateFlow<UiState<List<ChatMessage>>> = _activeContactId
        .flatMapLatest { contactId ->
            if (contactId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeMessages(currentUserId, contactId)
                    .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing messages", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load messages"))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val activeStoriesState: StateFlow<List<StoryItem>> = combine(
        repository.observeStories(currentUserId)
            .catch { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Error observing stories", error)
                emit(emptyList())
            },
        allContactsState,
        profileState,
        storyNowTickMs
    ) { rawStories, contactsState, profState, nowMs ->
        val myProfile = (profState as? UiState.Success)?.data
        val contacts = (contactsState as? UiState.Success)?.data.orEmpty()
        rawStories.filter { story ->
            if (story.expiresAtMs <= nowMs) return@filter false
            if (story.userId == currentUserId) return@filter true
            contacts.any { c ->
                !c.blocked &&
                    c.accountStatus != "banned" &&
                    c.threadType == "chat" &&
                    (c.peerUid == story.userId ||
                        (story.authorUsername.isNotBlank() && c.username.equals(story.authorUsername, ignoreCase = true)))
            }
        }.map { story ->
            if (story.userId == currentUserId && myProfile != null) {
                story.copy(
                    authorName = myProfile.displayName.ifBlank { story.authorName },
                    authorUsername = myProfile.username.ifBlank { story.authorUsername },
                    authorPhotoData = myProfile.photoData ?: story.authorPhotoData
                )
            } else {
                val matchingContact = contacts.firstOrNull { c ->
                    c.peerUid == story.userId ||
                        (story.authorUsername.isNotBlank() && c.username.equals(story.authorUsername, ignoreCase = true))
                }
                if (matchingContact != null) {
                    story.copy(
                        authorName = matchingContact.name.ifBlank { story.authorName },
                        authorPhotoData = matchingContact.photoData ?: story.authorPhotoData
                    )
                } else {
                    story
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )

    val seenStoryIdsState: StateFlow<Set<String>> = combine(
        _localSeenStoryIds,
        activeStoriesState
    ) { localSeen, stories ->
        val remoteSeen = stories
            .filter { currentUserId in it.viewedByUids }
            .map { it.id }
            .toSet()
        localSeen + remoteSeen
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptySet()
    )

    init {
        ensureInitialSetup()
        observeAutoSmartSuggestions()
        startIncomingSyncLoop()
        observeIncomingMessageAlerts()
        observeAdminLockAndAlerts()
        observeActiveBannedGuard()
        observeNetworkRecovery()
        startStoryExpiryLoop()
    }

    private fun observeActiveBannedGuard() {
        viewModelScope.launch {
            combine(allContactsState, _activeContactId) { state, activeId ->
                if (state is UiState.Success && activeId != null) {
                    state.data.firstOrNull { it.id == activeId }
                } else null
            }.collect { openThread ->
                if (openThread != null && openThread.isBanned) {
                    _activeContactId.value = null
                    _isContactTyping.value = false
                    _replyingToMessage.value = null
                    _quickSuggestions.value = emptyList()
                    lastSuggestedMessageId = null
                    _popupMessage.value = "This account is banned."
                }
            }
        }
    }

    private fun observeAdminLockAndAlerts() {
        viewModelScope.launch {
            isAdminSession.collect { isAdmin ->
                if (isAdmin) {
                    val user = Firebase.auth.currentUser
                    if (user != null && user.isEmailVerified && user.email.equals(ADMIN_GMAIL, ignoreCase = true)) {
                        repository.ensureAdminConfigLocked(user.uid, user.email.orEmpty())
                    }
                } else {
                    seenAdminAlertIds.clear()
                    _inAppAdminBanner.value = null
                }
            }
        }
        viewModelScope.launch {
            combine(isAdminSession, moderationAlertsState) { isAdmin, alerts ->
                isAdmin to alerts
            }.collect { (isAdmin, alerts) ->
                if (!isAdmin) return@collect
                val unseenPending = alerts.filter { it.status == "pending" && it.id.isNotBlank() && seenAdminAlertIds.add(it.id) }
                if (unseenPending.isNotEmpty()) {
                    val latest = unseenPending.first()
                    _adminNotificationEvents.tryEmit(latest)
                    if (isAppInForeground) {
                        _inAppAdminBanner.value = latest
                    }
                }
            }
        }
    }

    fun dismissInAppAdminBanner() {
        _inAppAdminBanner.value = null
    }

    private fun startIncomingSyncLoop() {
        incomingSyncJob?.cancel()
        incomingSyncJob = viewModelScope.launch {
            repository.startIncomingSync(currentUserId) { _activeContactId.value }
        }
    }

    private fun startStoryExpiryLoop() {
        viewModelScope.launch {
            repository.purgeExpiredStories(currentUserId)
            while (isActive) {
                delay(STORY_EXPIRY_CHECK_MS)
                storyNowTickMs.value = System.currentTimeMillis()
                repository.purgeExpiredStories(currentUserId)
            }
        }
    }

    private fun observeIncomingMessageAlerts() {
        viewModelScope.launch {
            repository.incomingAlerts.collect { alert ->
                val matchesUser = alert.recipientUserId.isEmpty() || alert.recipientUserId == currentUserId
                if (!matchesUser) return@collect
                if (!preferences.value.notificationsEnabled) return@collect
                val matchingContact = (allContactsState.value as? UiState.Success)?.data
                    ?.firstOrNull { it.id == alert.contactId }
                if (matchingContact?.muted == true || matchingContact?.blocked == true) return@collect
                val viewingSameThreadInForeground = isAppInForeground && _activeContactId.value == alert.contactId
                if (!viewingSameThreadInForeground) {
                    _incomingNotificationEvents.tryEmit(alert)
                    if (isAppInForeground) {
                        _inAppAlertBanner.value = alert
                    }
                }
            }
        }
    }

    fun dismissInAppAlertBanner() {
        _inAppAlertBanner.value = null
    }

    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            storyNowTickMs.value = System.currentTimeMillis()
        }
        presenceHeartbeatJob?.cancel()
        if (inForeground) {
            presenceHeartbeatJob = viewModelScope.launch {
                repository.setUserPresence(currentUserId, true)
                repository.purgeExpiredStories(currentUserId)
                while (isActive) {
                    delay(PRESENCE_HEARTBEAT_MS)
                    repository.setUserPresence(currentUserId, true)
                }
            }
        } else {
            viewModelScope.launch {
                repository.setUserPresence(currentUserId, false)
            }
        }
    }

    fun prepareImmediateLogout() {
        presenceHeartbeatJob?.cancel()
        incomingSyncJob?.cancel()
        repository.queueOfflinePresence(currentUserId)
    }

    suspend fun setOfflineBeforeLogout() {
        presenceHeartbeatJob?.cancel()
        repository.setUserPresence(currentUserId, false)
    }

    private fun observeNetworkRecovery() {
        viewModelScope.launch {
            var wasDisconnected = !networkStatus.value.isConnected
            networkStatus.collect { status ->
                if (status.isConnected) {
                    if (wasDisconnected) {
                        wasDisconnected = false
                        repository.onNetworkReconnected()
                        ensureInitialSetup()
                        if (incomingSyncJob?.isActive != true) {
                            startIncomingSyncLoop()
                        }
                    }
                } else {
                    wasDisconnected = true
                }
            }
        }
    }

    private fun showOfflinePopup() {
        _popupMessage.value = NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE
    }

    private fun isTransportCurrentlyOffline(): Boolean {
        val status = networkObserver?.currentStatus() ?: networkStatus.value
        return !status.isConnected
    }

    private fun requireVerifiedOnlineConnection(): Boolean = true

    fun reconnectCloudSync() {
        viewModelScope.launch {
            if (isTransportCurrentlyOffline()) {
                showOfflinePopup()
                return@launch
            }
            val reachable = (networkObserver == null || networkObserver.verifyInternetAccess()) &&
                repository.verifyActiveFirebaseConnection()
            if (!reachable) {
                showOfflinePopup()
                return@launch
            }
            repository.onNetworkReconnected()
            ensureInitialSetup()
            startIncomingSyncLoop()
            val label = networkObserver?.currentStatus()?.transportLabel ?: networkStatus.value.transportLabel
            _snackbarMessage.value = "Cloud sync active ($label)"
        }
    }

    private fun ensureInitialSetup() {
        if (!repository.requiresInitialCloudSetup) return
        viewModelScope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val user = Firebase.auth.currentUser
            val pendingPassword = PendingAuthCredentials.consumePassword()
            repository.ensureInitialUserSetup(
                userId = currentUserId,
                googleDisplayName = user?.displayName,
                googleEmail = user?.email,
                preferredDisplayName = prefs.preferredDisplayName,
                preferredUsername = prefs.preferredUsername,
                preferredDob = prefs.preferredDob,
                authFlowMode = prefs.authFlowMode,
                returningIdentifier = prefs.returningIdentifier,
                pendingPassword = pendingPassword
            ).onSuccess { outcome ->
                when (outcome) {
                    is UserSetupOutcome.Allowed -> {
                        preferencesRepository.clearPendingSignInFlow()
                    }
                    is UserSetupOutcome.Banned -> {
                        _isBannedAccount.value = true
                        _popupMessage.value = "Your ID is banned"
                    }
                    is UserSetupOutcome.Rejected -> {
                        _authRejectionMessage.value = outcome.message
                    }
                }
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to initialize profile."
            }
        }
    }

    /**
     * Automatically generates 3 context-aware Gemini smart replies above the input bar
     * when a chat opens or when a new message arrives from the other party.
     */
    private fun observeAutoSmartSuggestions() {
        viewModelScope.launch {
            combine(
                activeContact,
                activeMessagesState,
                preferences
            ) { contact, msgState, prefs ->
                Triple(contact, msgState, prefs.smartAiEnabled)
            }.collect { (contact, msgState, aiEnabled) ->
                if (contact == null || !aiEnabled || contact.accountStatus == "banned" || contact.blocked) return@collect
                if (msgState is UiState.Success) {
                    val lastMsg = msgState.data.lastOrNull()
                    val suggestionKey = "${contact.id}:${lastMsg?.id ?: "empty"}"
                    val shouldGenerate = (lastSuggestedMessageId == null || lastMsg?.sender == "them") &&
                        lastSuggestedMessageId != suggestionKey
                    if (shouldGenerate) {
                        lastSuggestedMessageId = suggestionKey
                        generateThreeSmartReplies(contact, msgState.data, showErrorFeedback = false)
                    }
                }
            }
        }
    }

    fun toggleSearch() {
        val next = !_isSearchVisible.value
        _isSearchVisible.value = next
        if (!next) _searchQuery.value = ""
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectPillFilter(filter: ChatPillFilter) {
        _selectedFilter.value = filter
    }

    fun openConversation(contactId: String) {
        val target = (allContactsState.value as? UiState.Success)?.data?.firstOrNull { it.id == contactId }
        // Completely block opening chat if the account is banned until recovered
        if (target?.isBanned == true) {
            _popupMessage.value = "This account is banned."
            return
        }
        if (target?.locked == true) {
            _popupMessage.value = "Chat is locked. Tap 'Unlock chat' to open."
            return
        }
        _activeContactId.value = contactId
        _quickSuggestions.value = emptyList()
        lastSuggestedMessageId = null
        viewModelScope.launch {
            repository.markThreadRead(currentUserId, contactId)
        }
    }

    fun closeConversation() {
        val prevId = _activeContactId.value
        _activeContactId.value = null
        _isContactTyping.value = false
        _quickSuggestions.value = emptyList()
        lastSuggestedMessageId = null
        if (!prevId.isNullOrBlank()) {
            viewModelScope.launch {
                repository.setContactTyping(currentUserId, prevId, false)
            }
        }
    }

    fun toggleLockChat(thread: ChatThread) {
        val nextLocked = !thread.locked
        viewModelScope.launch {
            repository.toggleLockChat(currentUserId, thread.id, nextLocked)
                .onSuccess {
                    if (nextLocked && _activeContactId.value == thread.id) {
                        _activeContactId.value = null
                    }
                    _popupMessage.value = if (nextLocked) "Chat locked" else "Chat unlocked"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update chat lock."
                }
        }
    }

    fun toggleBlockChat(thread: ChatThread) {
        val nextBlocked = !thread.blocked
        viewModelScope.launch {
            repository.toggleBlockChat(currentUserId, thread.id, nextBlocked)
                .onSuccess {
                    _popupMessage.value = if (nextBlocked) "${thread.name} blocked" else "${thread.name} unblocked"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update block state."
                }
        }
    }

    fun toggleArchiveChat(thread: ChatThread) {
        val nextArchived = !thread.archived
        viewModelScope.launch {
            repository.toggleArchiveChat(currentUserId, thread.id, nextArchived)
                .onSuccess {
                    if (nextArchived && _activeContactId.value == thread.id) {
                        _activeContactId.value = null
                    }
                    _popupMessage.value = if (nextArchived) "Chat moved to Archived" else "Chat unarchived"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update archive status."
                }
        }
    }

    fun renameChat(thread: ChatThread, newName: String) {
        val cleanName = newName.trim().take(60)
        if (cleanName.isEmpty()) {
            _snackbarMessage.value = "Chat name cannot be empty."
            return
        }
        viewModelScope.launch {
            repository.renameChat(currentUserId, thread.id, cleanName)
                .onSuccess {
                    _popupMessage.value = "Chat renamed"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to rename chat."
                }
        }
    }

    fun deleteChatPermanently(thread: ChatThread) {
        viewModelScope.launch {
            repository.deleteChatPermanently(currentUserId, thread.id)
                .onSuccess {
                    if (_activeContactId.value == thread.id) {
                        _activeContactId.value = null
                    }
                    _popupMessage.value = "Chat deleted permanently"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to delete chat."
                }
        }
    }

    fun toggleMuteChat(thread: ChatThread) {
        val nextMuted = !thread.muted
        viewModelScope.launch {
            repository.toggleMuteChat(currentUserId, thread.id, nextMuted)
                .onSuccess {
                    _popupMessage.value = if (nextMuted) "Notifications muted" else "Notifications unmuted"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update mute setting."
                }
        }
    }

    fun setDisappearingMessages(thread: ChatThread, seconds: Long) {
        viewModelScope.launch {
            repository.setDisappearingMessages(currentUserId, thread.id, seconds)
                .onSuccess {
                    _popupMessage.value = when (seconds) {
                        86_400L -> "Disappearing messages: 24 hours"
                        604_800L -> "Disappearing messages: 7 days"
                        7_776_000L -> "Disappearing messages: 90 days"
                        else -> "Disappearing messages turned off"
                    }
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update disappearing messages."
                }
        }
    }

    fun setChatTheme(thread: ChatThread, themeKey: String) {
        viewModelScope.launch {
            repository.setChatTheme(currentUserId, thread.id, themeKey)
                .onSuccess {
                    val label = com.example.data.model.ChatThemeStyle.fromKey(themeKey).label
                    _popupMessage.value = "Chat theme: $label"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update chat theme."
                }
        }
    }

    fun clearChatMessages(thread: ChatThread) {
        viewModelScope.launch {
            repository.clearChatMessages(currentUserId, thread.id)
                .onSuccess {
                    _popupMessage.value = "Chat messages cleared"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to clear messages."
                }
        }
    }

    fun setReplyingToMessage(message: ChatMessage?) {
        _replyingToMessage.value = message
    }

    fun cancelReply() {
        _replyingToMessage.value = null
    }

    private var typingJob: Job? = null

    fun updateTypingStatus(isTyping: Boolean) {
        val contactId = _activeContactId.value ?: return
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            repository.setContactTyping(currentUserId, contactId, isTyping)
            if (isTyping) {
                delay(3000L)
                repository.setContactTyping(currentUserId, contactId, false)
            }
        }
    }

    fun deleteMessage(message: ChatMessage) {
        val contact = activeContact.value ?: return
        viewModelScope.launch {
            repository.deleteMessage(currentUserId, contact.id, message.id)
                .onSuccess {
                    _popupMessage.value = "Message deleted permanently"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to delete message."
                }
        }
    }

    fun sendMessage(text: String) {
        val contact = activeContact.value ?: return
        if (contact.accountStatus == "banned") {
            _popupMessage.value = "This account is banned."
            return
        }
        if (contact.blocked) {
            _popupMessage.value = "This user is blocked. Unblock to send messages."
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val replying = _replyingToMessage.value
        _replyingToMessage.value = null
        _quickSuggestions.value = emptyList()
        viewModelScope.launch {
            typingJob?.cancel()
            val result = repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = trimmed,
                aiGenerated = false,
                replyToText = replying?.text?.take(200),
                replyToSender = replying?.sender?.let { if (it == "me") "You" else contact.name },
                replyToMessageId = replying?.id
            )
            result.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to send message."
            }
        }
    }

    fun sendVoiceMessage(mediaData: String, durationSec: Long) {
        val contact = activeContact.value ?: return
        if (contact.accountStatus == "banned") {
            _popupMessage.value = "This account is banned."
            return
        }
        if (contact.blocked) {
            _popupMessage.value = "This user is blocked. Unblock to send voice messages."
            return
        }
        if (mediaData.isBlank()) return
        val safeDuration = durationSec.coerceIn(1L, 600L)
        _quickSuggestions.value = emptyList()
        viewModelScope.launch {
            repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = "",
                aiGenerated = false,
                messageType = "voice",
                mediaData = mediaData,
                durationSec = safeDuration
            ).onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to send voice message."
            }
        }
    }

    fun sendImageMessageFromUri(context: Context, uri: Uri) {
        val contact = activeContact.value ?: return
        if (contact.accountStatus == "banned") {
            _popupMessage.value = "This account is banned."
            return
        }
        if (contact.blocked) {
            _popupMessage.value = "This user is blocked. Unblock to send photos."
            return
        }
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.IO) {
                encodeStoryMediaDataUri(context, uri)
            }
            if (encoded == null) {
                _snackbarMessage.value = "Unable to process selected photo."
                return@launch
            }
            _quickSuggestions.value = emptyList()
            repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = "📷 Photo",
                aiGenerated = false,
                messageType = "image",
                mediaData = encoded
            ).onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to send photo."
            }
        }
    }

    fun sendImageMessageFromBitmap(bitmap: Bitmap) {
        val contact = activeContact.value ?: return
        if (contact.accountStatus == "banned") {
            _popupMessage.value = "This account is banned."
            return
        }
        if (contact.blocked) {
            _popupMessage.value = "This user is blocked. Unblock to send photos."
            return
        }
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.IO) {
                encodeBitmapToDataUri(bitmap)
            }
            if (encoded == null) {
                _snackbarMessage.value = "Unable to encode captured photo."
                return@launch
            }
            _quickSuggestions.value = emptyList()
            repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = "📷 Photo",
                aiGenerated = false,
                messageType = "image",
                mediaData = encoded
            ).onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to send photo."
            }
        }
    }

    fun sendFileAttachmentMessage(context: Context, uri: Uri) {
        val contact = activeContact.value ?: return
        if (contact.accountStatus == "banned") {
            _popupMessage.value = "This account is banned."
            return
        }
        if (contact.blocked) {
            _popupMessage.value = "This user is blocked. Unblock to send attachments."
            return
        }
        viewModelScope.launch {
            val fileName = withContext(Dispatchers.IO) {
                resolveDisplayFileName(context, uri)
            }
            _quickSuggestions.value = emptyList()
            repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = "📎 $fileName",
                aiGenerated = false,
                messageType = "file"
            ).onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to send attachment."
            }
        }
    }

    fun logVoiceCallAction() {
        val contact = activeContact.value ?: return
        if (contact.accountStatus == "banned" || contact.blocked) return
        viewModelScope.launch {
            repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = "📞 Voice call",
                aiGenerated = false,
                messageType = "call"
            )
        }
    }

    fun requestQuickSuggestions() {
        val contact = activeContact.value ?: return
        val history = (activeMessagesState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            generateThreeSmartReplies(contact, history, showErrorFeedback = true)
        }
    }

    fun dismissQuickSuggestions() {
        _quickSuggestions.value = emptyList()
    }

    private suspend fun generateThreeSmartReplies(
        contact: ChatThread,
        history: List<ChatMessage>,
        showErrorFeedback: Boolean = false
    ) {
        if (_isGeneratingSuggestions.value) return
        _isGeneratingSuggestions.value = true
        try {
            val myName = (profileState.value as? UiState.Success)?.data?.displayName ?: "Friend"
            smartReplyRepository.generateQuickSuggestions(
                userName = myName,
                contactName = contact.name,
                recentMessages = history
            ).onSuccess { chips ->
                _quickSuggestions.value = chips.take(3)
            }.onFailure { err ->
                Log.w(TAG, "Smart suggestions unavailable: ${err.localizedMessage}", err)
                if (showErrorFeedback) {
                    val msg = if (!networkStatus.value.isConnected) {
                        "Connect to the internet for smart suggestions."
                    } else {
                        "Smart suggestions temporarily unavailable."
                    }
                    _snackbarMessage.value = msg
                }
            }
        } finally {
            _isGeneratingSuggestions.value = false
        }
    }

    fun reportActiveContact(reason: String = "Other") {
        val contact = activeContact.value ?: return
        reportContact(contact, reason)
    }

    fun reportContact(contact: ChatThread, reason: String = "Other") {
        if (isTransportCurrentlyOffline()) {
            showOfflinePopup()
            return
        }
        val myHandle = (profileState.value as? UiState.Success)?.data?.username.orEmpty()
            .ifBlank { currentUserId }

        viewModelScope.launch {
            repository.reportUserAccount(
                userId = currentUserId,
                contact = contact,
                reporterUsername = myHandle,
                reason = reason.ifBlank { "Other" }
            ).onSuccess { outcome ->
                _popupMessage.value = outcome.message
            }.onFailure { err ->
                _popupMessage.value = err.localizedMessage ?: "Unable to submit report."
            }
        }
    }

    fun ignoreModerationAlert(alertId: String) {
        if (!isAdminSession.value) return
        _inAppAdminBanner.value = null
        viewModelScope.launch {
            _isAdminActionLoading.value = true
            try {
                repository.ignoreModerationAlert(alertId)
                    .onSuccess {
                        _popupMessage.value = "Alert ignored."
                    }
                    .onFailure { err ->
                        _popupMessage.value = err.localizedMessage ?: "Unable to ignore alert."
                    }
            } finally {
                _isAdminActionLoading.value = false
            }
        }
    }

    fun banUserFromAlert(alert: ModerationAlert) {
        if (!isAdminSession.value) return
        _inAppAdminBanner.value = null
        viewModelScope.launch {
            _isAdminActionLoading.value = true
            try {
                repository.banUserFromAlert(alert)
                    .onSuccess {
                        _popupMessage.value = "@${alert.reportedUsername} banned across sync."
                    }
                    .onFailure { err ->
                        _popupMessage.value = err.localizedMessage ?: "Unable to ban user."
                    }
            } finally {
                _isAdminActionLoading.value = false
            }
        }
    }

    fun recoverUserFromAlert(alert: ModerationAlert) {
        if (!isAdminSession.value) return
        _inAppAdminBanner.value = null
        viewModelScope.launch {
            _isAdminActionLoading.value = true
            try {
                repository.recoverUserFromAlert(alert)
                    .onSuccess {
                        val handleLabel = alert.reportedUsername.ifBlank { alert.reportedDisplayName.ifBlank { "Account" } }
                        _popupMessage.value = "@$handleLabel recovered and removed from banned list."
                    }
                    .onFailure { err ->
                        _popupMessage.value = err.localizedMessage ?: "Unable to recover user."
                    }
            } finally {
                _isAdminActionLoading.value = false
            }
        }
    }

    fun adminVerifyUser(user: AdminUserRecord, verified: Boolean) {
        if (!isAdminSession.value) return
        viewModelScope.launch {
            _isAdminActionLoading.value = true
            try {
                repository.adminVerifyUser(user.uid, user.username, verified)
                    .onFailure {
                        _popupMessage.value = "Failed to update verified badge: ${it.localizedMessage ?: "Sync error"}"
                    }
                    .onSuccess {
                        _popupMessage.value = if (verified) "Granted blue tick to @${user.username}" else "Removed blue tick from @${user.username}"
                    }
            } finally {
                _isAdminActionLoading.value = false
            }
        }
    }

    fun adminBanUser(user: AdminUserRecord) {
        if (!isAdminSession.value) return
        viewModelScope.launch {
            _isAdminActionLoading.value = true
            try {
                repository.adminBanUser(user.uid, user.username)
                    .onFailure {
                        _popupMessage.value = "Failed to ban user: ${it.localizedMessage ?: "Sync error"}"
                    }
                    .onSuccess {
                        _popupMessage.value = "Banned @${user.username} account"
                    }
            } finally {
                _isAdminActionLoading.value = false
            }
        }
    }

    fun adminUnbanUser(user: AdminUserRecord) {
        if (!isAdminSession.value) return
        viewModelScope.launch {
            _isAdminActionLoading.value = true
            try {
                repository.adminUnbanUser(user.uid, user.username)
                    .onFailure {
                        _popupMessage.value = "Failed to restore user: ${it.localizedMessage ?: "Sync error"}"
                    }
                    .onSuccess {
                        val handleLabel = user.username.ifBlank { user.displayName.ifBlank { "Account" } }
                        _popupMessage.value = "@$handleLabel recovered and removed from banned list."
                    }
            } finally {
                _isAdminActionLoading.value = false
            }
        }
    }

    fun findUserByUsername(username: String) {
        val rawClean = username.trim().removePrefix("@").lowercase()
        if (rawClean.isEmpty()) {
            _usernameSearchState.value = UsernameSearchState.NotFound("Please enter a username.")
            return
        }
        if (!rawClean.matches(USERNAME_FORMAT_REGEX)) {
            _usernameSearchState.value = UsernameSearchState.NotFound(
                "No spaces or special characters allowed (use letters, numbers, _, ., -)."
            )
            return
        }
        val clean = com.example.data.repository.NomiChatRepository.exactUsernameOrNull(username)
        if (clean == null) {
            _usernameSearchState.value = UsernameSearchState.NotFound("Account Not Found")
            return
        }
        val myHandle = (profileState.value as? UiState.Success)?.data?.username.orEmpty()
        viewModelScope.launch {
            _usernameSearchState.value = UsernameSearchState.Loading
            val existingContacts = (allContactsState.value as? UiState.Success)?.data.orEmpty()
            val existingThread = existingContacts.firstOrNull { c ->
                c.threadType == "chat" && c.username.equals(clean, ignoreCase = true)
            }
            if (existingThread != null) {
                _usernameSearchState.value = UsernameSearchState.NotFound(
                    "This username is already added. You cannot add it again."
                )
                return@launch
            }
            repository.lookupUserByUsername(clean, viewerUserId = currentUserId)
                .onSuccess { found ->
                    _usernameSearchState.value = when {
                        found == null || !found.username.equals(clean, ignoreCase = true) ->
                            UsernameSearchState.NotFound("Account Not Found")
                        found.ownerUid == currentUserId ||
                            (myHandle.isNotBlank() && found.username.equals(myHandle, ignoreCase = true)) ->
                            UsernameSearchState.NotFound("You cannot add your own username.")
                        else -> UsernameSearchState.Found(found)
                    }
                }
                .onFailure { _ ->
                    _usernameSearchState.value = UsernameSearchState.NotFound("Account Not Found")
                }
        }
    }

    fun resetUsernameSearch() {
        _usernameSearchState.value = UsernameSearchState.Idle
    }

    fun addDirectChatByUsername(
        username: String,
        onSuccess: () -> Unit
    ) {
        val rawClean = username.trim().removePrefix("@").lowercase()
        if (rawClean.isEmpty()) {
            _usernameSearchState.value = UsernameSearchState.NotFound("Please enter a username.")
            return
        }
        if (!rawClean.matches(USERNAME_FORMAT_REGEX)) {
            _usernameSearchState.value = UsernameSearchState.NotFound(
                "No spaces or special characters allowed (use letters, numbers, _, ., -)."
            )
            return
        }
        val clean = com.example.data.repository.NomiChatRepository.exactUsernameOrNull(username)
        if (clean == null) {
            _usernameSearchState.value = UsernameSearchState.NotFound("Account Not Found")
            return
        }
        val cachedFound = (_usernameSearchState.value as? UsernameSearchState.Found)?.result
            ?.takeIf { it.username.equals(clean, ignoreCase = true) }
        val myHandle = (profileState.value as? UiState.Success)?.data?.username.orEmpty()
        viewModelScope.launch {
            _usernameSearchState.value = UsernameSearchState.Loading
            val existingContacts = (allContactsState.value as? UiState.Success)?.data.orEmpty()
            val existingThread = existingContacts.firstOrNull { c ->
                c.threadType == "chat" && c.username.equals(clean, ignoreCase = true)
            }
            if (existingThread != null) {
                _usernameSearchState.value = UsernameSearchState.NotFound(
                    "This username is already added. You cannot add it again."
                )
                return@launch
            }
            val found = if (cachedFound != null) {
                cachedFound
            } else {
                val lookupResult = repository.lookupUserByUsername(clean, viewerUserId = currentUserId)
                if (lookupResult.isFailure) {
                    val errMsg = lookupResult.exceptionOrNull()?.localizedMessage?.trim().orEmpty()
                    _usernameSearchState.value = UsernameSearchState.NotFound(
                        errMsg.ifBlank { "Could not verify username right now. Please try again." }
                    )
                    return@launch
                }
                lookupResult.getOrNull()
            }
            if (found == null || !found.username.equals(clean, ignoreCase = true)) {
                _usernameSearchState.value = UsernameSearchState.NotFound("Account Not Found")
                return@launch
            }
            if (found.ownerUid == currentUserId ||
                (myHandle.isNotBlank() && found.username.equals(myHandle, ignoreCase = true))
            ) {
                _usernameSearchState.value = UsernameSearchState.NotFound("You cannot add your own username.")
                return@launch
            }

            repository.addContact(
                userId = currentUserId,
                name = found.displayName,
                username = found.username,
                phone = "",
                threadType = "chat",
                peerUid = found.ownerUid,
                isRegisteredPeer = true
            ).onSuccess {
                _usernameSearchState.value = UsernameSearchState.Idle
                _selectedFilter.value = ChatPillFilter.CHATS
                _popupMessage.value = if (found.isBanned) {
                    "Added @${found.username} (Banned — chat locked until recovered)."
                } else {
                    "Username added successfully."
                }
                onSuccess()
            }.onFailure { err ->
                val msg = err.localizedMessage?.trim().orEmpty()
                val errorText = when {
                    msg.contains("already added", ignoreCase = true) || msg.contains("already taken", ignoreCase = true) ->
                        "This username is already added. You cannot add it again."
                    msg == "Account Not Found" ->
                        "Account Not Found"
                    msg.isNotBlank() ->
                        msg
                    else ->
                        "Could not add username. Please try again."
                }
                _usernameSearchState.value = UsernameSearchState.NotFound(errorText)
            }
        }
    }

    fun addGroupChat(
        name: String,
        username: String = "",
        onSuccess: () -> Unit
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _snackbarMessage.value = "Group name cannot be empty."
            return
        }
        viewModelScope.launch {
            repository.addContact(
                userId = currentUserId,
                name = cleanName,
                username = username,
                phone = "",
                threadType = "group",
                peerUid = "",
                isRegisteredPeer = false
            ).onSuccess {
                _usernameSearchState.value = UsernameSearchState.Idle
                _selectedFilter.value = ChatPillFilter.GROUPS
                _popupMessage.value = "Group added"
                onSuccess()
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Could not create group."
            }
        }
    }

    fun updateProfileField(
        displayName: String? = null,
        username: String? = null,
        bio: String? = null,
        dob: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        if (_isSavingProfile.value) return
        _profileSaveError.value = null
        viewModelScope.launch {
            _isSavingProfile.value = true
            try {
                repository.updateProfile(
                    userId = currentUserId,
                    displayName = displayName,
                    username = username,
                    bio = bio,
                    dob = dob
                ).onSuccess { movedToUsername ->
                    if (!movedToUsername.isNullOrBlank() && movedToUsername != currentUserId) {
                        preferencesRepository.saveActiveUsername(movedToUsername)
                    }
                    _profileSaveError.value = null
                    _snackbarMessage.value = "Profile updated"
                    onSuccess()
                }.onFailure { err ->
                    val errMsg = err.localizedMessage ?: "Failed to update profile."
                    _profileSaveError.value = errMsg
                    _snackbarMessage.value = errMsg
                }
            } finally {
                _isSavingProfile.value = false
            }
        }
    }

    fun updateProfilePhotoFromUri(context: Context, uri: Uri) {
        if (_isUploadingPhoto.value) return
        viewModelScope.launch {
            _isUploadingPhoto.value = true
            try {
                val encoded = withContext(Dispatchers.IO) {
                    encodeAvatarDataUri(context, uri)
                }
                if (encoded == null) {
                    _snackbarMessage.value = "Unable to process selected image."
                    return@launch
                }
                repository.updateProfile(
                    userId = currentUserId,
                    photoData = encoded
                ).onSuccess {
                    _snackbarMessage.value = "Profile photo updated"
                }.onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to upload photo."
                }
            } finally {
                _isUploadingPhoto.value = false
            }
        }
    }

    fun updateProfileBannerFromUri(context: Context, uri: Uri) {
        if (_isUploadingBanner.value) return
        viewModelScope.launch {
            _isUploadingBanner.value = true
            try {
                val encoded = withContext(Dispatchers.IO) {
                    encodeBannerDataUri(context, uri)
                }
                if (encoded == null) {
                    _snackbarMessage.value = "Unable to process selected banner image."
                    return@launch
                }
                repository.updateProfile(
                    userId = currentUserId,
                    bannerData = encoded
                ).onSuccess {
                    _popupMessage.value = "Profile banner updated"
                }.onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to upload banner."
                }
            } finally {
                _isUploadingBanner.value = false
            }
        }
    }

    fun updatePrivacySettings(
        visibility: ProfileVisibilityMode,
        allowedViewerIds: List<String>,
        maxViewers: Long,
        showOnlineStatus: Boolean = true
    ) {
        viewModelScope.launch {
            repository.updatePrivacySettings(
                userId = currentUserId,
                profileVisibility = visibility.key,
                allowedViewerUids = allowedViewerIds,
                maxProfileViewers = maxViewers,
                showOnlineStatus = showOnlineStatus
            ).onSuccess {
                _popupMessage.value = "Privacy settings updated"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Unable to update privacy settings."
            }
        }
    }

    fun changePassword(
        currentPassword: String,
        newPassword: String,
        onSuccess: () -> Unit = {}
    ) {
        if (_isChangingPassword.value) return
        viewModelScope.launch {
            _isChangingPassword.value = true
            try {
                repository.changePassword(
                    userId = currentUserId,
                    currentPassword = currentPassword,
                    newPassword = newPassword
                ).onSuccess {
                    _popupMessage.value = "Password updated successfully"
                    onSuccess()
                }.onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Unable to update password."
                }
            } finally {
                _isChangingPassword.value = false
            }
        }
    }

    fun refreshAllData() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.onNetworkReconnected()
                val uid = currentUserId
                if (uid.isNotBlank()) {
                    repository.purgeExpiredStories(uid)
                    repository.refreshUserData(uid).onFailure { err ->
                        _snackbarMessage.value = err.localizedMessage ?: "Refresh failed."
                    }
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
            repository.syncAccountSettings(currentUserId, themeMode = mode)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotificationsEnabled(enabled)
            repository.syncAccountSettings(currentUserId, notificationsEnabled = enabled)
        }
    }

    fun setSmartAiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSmartAiEnabled(enabled)
            repository.syncAccountSettings(currentUserId, smartAiEnabled = enabled)
        }
    }

    fun setAppLockPin(pin: String) {
        viewModelScope.launch {
            val hash = preferencesRepository.setAppLockPin(pin)
            repository.syncAccountSettings(currentUserId, appLockPinHash = hash)
            _isAppUnlocked.value = true
            _snackbarMessage.value = if (pin.isBlank()) "App Lock PIN disabled" else "App Lock PIN updated"
        }
    }

    fun verifyPin(pin: String): Boolean {
        val ok = preferencesRepository.verifyAppLockPin(pin, preferences.value.appLockPinHash)
        if (ok) _isAppUnlocked.value = true
        return ok
    }

    fun postStoryFromUri(
        context: Context,
        uri: Uri?,
        caption: String,
        storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key,
        onSuccess: () -> Unit
    ) {
        postRichStory(
            context = context,
            uri = uri,
            caption = caption,
            storyFont = storyFont,
            onSuccess = onSuccess
        )
    }

    fun postRichStory(
        context: Context,
        uri: Uri?,
        caption: String,
        storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key,
        musicTag: String = "",
        overlayText: String = "",
        overlayColorHex: Long = 0xFFFFFFFFL,
        overlayYFraction: Float = 0.5f,
        stickersJson: String = "",
        drawingJson: String = "",
        cropMode: String = "crop",
        rotationDegrees: Int = 0,
        onSuccess: () -> Unit
    ) {
        if (_isPostingStory.value) return
        val cleanCaption = caption.trim()
        val cleanOverlayText = overlayText.trim()
        if (uri == null && cleanCaption.isEmpty() && cleanOverlayText.isEmpty() && drawingJson.isBlank() && stickersJson.isBlank()) {
            _snackbarMessage.value = "Choose a photo/video or add text, stickers, or drawing."
            return
        }
        viewModelScope.launch {
            _isPostingStory.value = true
            try {
                val (encodedMedia, resolvedMediaType) = if (uri != null) {
                    withContext(Dispatchers.IO) {
                        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                        if (mime.startsWith("video/")) {
                            encodeVideoStoryDataUri(context, uri)
                        } else {
                            encodeStoryMediaDataUri(context, uri) to "image"
                        }
                    }
                } else {
                    "text_card:emerald" to "text"
                }
                if (encodedMedia == null) {
                    _snackbarMessage.value = "Unable to process selected media."
                    return@launch
                }
                repository.postStory(
                    userId = currentUserId,
                    mediaData = encodedMedia,
                    caption = cleanCaption,
                    storyFont = StoryFontStyle.fromKey(storyFont).key,
                    mediaType = resolvedMediaType,
                    musicTag = musicTag.trim().take(80),
                    overlayText = cleanOverlayText.take(180),
                    overlayColorHex = overlayColorHex,
                    overlayYFraction = overlayYFraction.toDouble().coerceIn(0.12, 0.88),
                    stickersJson = stickersJson,
                    drawingJson = drawingJson,
                    cropMode = if (cropMode == "fit") "fit" else "crop",
                    rotationDegrees = ((rotationDegrees % 360 + 360) % 360).toLong()
                ).onSuccess {
                    storyNowTickMs.value = System.currentTimeMillis()
                    _popupMessage.value = "Story posted for 24 hours"
                    onSuccess()
                }.onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to post story."
                }
            } finally {
                _isPostingStory.value = false
            }
        }
    }

    fun observeStoryViews(storyId: String): Flow<List<StoryViewRecord>> {
        return repository.observeStoryViews(currentUserId, storyId)
            .catch { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Error observing story views", error)
                emit(emptyList())
            }
    }

    fun recordStoryView(story: StoryItem) {
        if (story.id.isBlank()) return
        _localSeenStoryIds.value = _localSeenStoryIds.value + story.id
        if (story.userId != currentUserId && currentUserId !in story.viewedByUids) {
            viewModelScope.launch {
                repository.recordStoryView(currentUserId, story.id)
            }
        }
    }

    fun toggleStoryLike(story: StoryItem) {
        if (story.id.isBlank()) return
        val currentlyLiked = currentUserId in story.likedByUids
        viewModelScope.launch {
            repository.reactToStory(
                userId = currentUserId,
                storyId = story.id,
                liked = !currentlyLiked
            ).onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Unable to update story like."
            }
        }
    }

    fun sendStoryReaction(story: StoryItem, emoji: String) {
        val cleanEmoji = emoji.trim()
        if (story.id.isBlank() || cleanEmoji.isEmpty()) return
        viewModelScope.launch {
            repository.reactToStory(
                userId = currentUserId,
                storyId = story.id,
                reactionEmoji = cleanEmoji
            )
            if (story.userId != currentUserId) {
                repository.replyToStory(currentUserId, story, cleanEmoji)
            }
            _popupMessage.value = "Reacted $cleanEmoji to ${story.authorName}'s story"
        }
    }

    fun replyToStory(story: StoryItem, replyText: String) {
        val clean = replyText.trim()
        if (story.id.isBlank() || clean.isEmpty()) return
        viewModelScope.launch {
            repository.replyToStory(currentUserId, story, clean)
                .onSuccess {
                    _popupMessage.value = "Reply sent to ${story.authorName}"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to send story reply."
                }
        }
    }

    fun deleteStory(story: StoryItem) {
        viewModelScope.launch {
            repository.deleteStory(currentUserId, story.id)
                .onSuccess {
                    storyNowTickMs.value = System.currentTimeMillis()
                    _popupMessage.value = "Story deleted"
                }
                .onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Failed to delete story."
                }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun clearPopup() {
        _popupMessage.value = null
    }

    fun clearProfileSaveError() {
        _profileSaveError.value = null
    }

    fun clearAuthRejection() {
        _authRejectionMessage.value = null
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decodedFast = runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val srcW = info.size.width
                    val srcH = info.size.height
                    if (srcW > 0 && srcH > 0) {
                        val longest = max(srcW, srcH)
                        if (longest > maxDim) {
                            val scale = maxDim.toFloat() / longest.toFloat()
                            decoder.setTargetSize(
                                (srcW * scale).toInt().coerceAtLeast(1),
                                (srcH * scale).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                }
            }.getOrNull()
            if (decodedFast != null) return decodedFast
        }
        return decodeLegacySampledBitmap(context, uri, maxDim)
    }

    private fun decodeLegacySampledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var inSampleSize = 1
        while ((srcW / (inSampleSize * 2)) >= maxDim || (srcH / (inSampleSize * 2)) >= maxDim) {
            inSampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val rotationDegrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { exifStream ->
                when (
                    ExifInterface(exifStream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        return if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    }

    private fun encodeAvatarDataUri(context: Context, uri: Uri): String? {
        val original = decodeSampledBitmap(context, uri, maxDim = 280) ?: return null
        return try {
            compressBitmapUnderLimit(original, initialMaxDim = 280, minMaxDim = 80, maxChars = 110_000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode avatar image", e)
            null
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun encodeStoryMediaDataUri(context: Context, uri: Uri): String? {
        val original = decodeSampledBitmap(context, uri, maxDim = 720) ?: return null
        return try {
            encodeBitmapToDataUri(original)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode story media", e)
            null
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun encodeVideoStoryDataUri(context: Context, uri: Uri): Pair<String?, String> {
        // 1. If the raw MP4 fits comfortably within Firestore's 1 MiB document budget, encode directly
        val directBytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                var total = 0
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_STORY_VIDEO_RAW_BYTES) return@use null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrNull()
        if (directBytes != null && directBytes.isNotEmpty()) {
            val encoded = "data:video/mp4;base64," + Base64.encodeToString(directBytes, Base64.NO_WRAP)
            if (encoded.length <= MAX_STORY_MEDIA_DATA_CHARS) {
                return encoded to "video"
            }
        }

        // 2. Trim/remux tracks cleanly to fit compact cloud sync budget using MediaExtractor + MediaMuxer
        val trimmedFile = File(context.cacheDir, "story_trim_${System.currentTimeMillis()}.mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(context, uri, null)
            muxer = MediaMuxer(trimmedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            var hasVideoTrack = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val dstIdx = muxer.addTrack(format)
                    trackMap[i] = dstIdx
                    extractor.selectTrack(i)
                    if (mime.startsWith("video/")) hasVideoTrack = true
                }
            }
            if (hasVideoTrack && trackMap.isNotEmpty()) {
                muxer.start()
                muxerStarted = true
                val byteBuffer = ByteBuffer.allocate(256 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()
                var accumulatedBytes = 0L
                while (true) {
                    val sampleTrack = extractor.sampleTrackIndex
                    if (sampleTrack < 0) break
                    val sampleSize = extractor.readSampleData(byteBuffer, 0)
                    if (sampleSize < 0) break
                    if (accumulatedBytes + sampleSize > MAX_STORY_VIDEO_TRIM_BYTES) break
                    val dstIdx = trackMap[sampleTrack]
                    if (dstIdx != null) {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstIdx, byteBuffer, bufferInfo)
                        accumulatedBytes += sampleSize
                    }
                    extractor.advance()
                }
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                    muxerStarted = false
                }
                val bytes = trimmedFile.takeIf { it.exists() && it.length() in 1..MAX_STORY_VIDEO_RAW_BYTES }
                    ?.readBytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val encoded = "data:video/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (encoded.length <= MAX_STORY_MEDIA_DATA_CHARS) {
                        return encoded to "video"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video remux trim fallback to frame", e)
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { trimmedFile.delete() }
        }

        // 3. Poster-frame JPEG fallback so large unsupported videos still share cleanly
        val posterBitmap = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
        return if (posterBitmap != null) {
            encodeBitmapToDataUri(posterBitmap) to "image"
        } else {
            null to "image"
        }
    }

    private fun encodeBitmapToDataUri(original: Bitmap): String? {
        return compressBitmapUnderLimit(original, initialMaxDim = 720, minMaxDim = 140, maxChars = 200_000)
    }

    private fun resolveDisplayFileName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
        return queried?.trim()?.takeIf { it.isNotBlank() }?.take(80)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.take(80)
            ?: "Document"
    }

    private fun encodeBannerDataUri(context: Context, uri: Uri): String? {
        val original = decodeSampledBitmap(context, uri, maxDim = 560) ?: return null
        return try {
            compressBitmapUnderLimit(original, initialMaxDim = 560, minMaxDim = 120, maxChars = 120_000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode banner image", e)
            null
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun compressBitmapUnderLimit(
        original: Bitmap,
        initialMaxDim: Int,
        minMaxDim: Int,
        maxChars: Int
    ): String? {
        return try {
            var maxDim = initialMaxDim
            var bestCandidate: String? = null
            val baos = ByteArrayOutputStream(32_768)
            while (maxDim >= minMaxDim) {
                val scale = maxDim.toFloat() / max(original.width, original.height).toFloat()
                val targetW = if (scale < 1f) (original.width * scale).toInt().coerceAtLeast(1) else original.width
                val targetH = if (scale < 1f) (original.height * scale).toInt().coerceAtLeast(1) else original.height
                val scaled = if (targetW == original.width && targetH == original.height) {
                    original
                } else {
                    Bitmap.createScaledBitmap(original, targetW, targetH, true)
                }
                var quality = 78
                while (quality >= 24) {
                    baos.reset()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val candidate = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    bestCandidate = candidate
                    if (candidate.length <= maxChars) {
                        if (scaled !== original) scaled.recycle()
                        return candidate
                    }
                    quality -= 18
                }
                if (scaled !== original) scaled.recycle()
                maxDim = (maxDim * 2) / 3
            }
            bestCandidate?.takeIf { it.length <= maxChars + 20_000 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress bitmap media", e)
            null
        }
    }

    private companion object {
        const val TAG = "NomiChatVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
        const val PRESENCE_HEARTBEAT_MS = 60_000L
        const val STORY_EXPIRY_CHECK_MS = 30_000L
        const val MAX_STORY_VIDEO_RAW_BYTES = 650_000
        const val MAX_STORY_VIDEO_TRIM_BYTES = 600_000L
        const val MAX_STORY_MEDIA_DATA_CHARS = 900_000
        val USERNAME_FORMAT_REGEX = Regex("^[a-z0-9_.-]+$")
    }
}

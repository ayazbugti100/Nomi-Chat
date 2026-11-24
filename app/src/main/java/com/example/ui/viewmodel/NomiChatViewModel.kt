package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.SmartReplyRepository
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPillFilter
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.ProfileVisibilityMode
import com.example.data.model.StoryFontStyle
import com.example.data.model.StoryItem
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
import kotlin.math.max

sealed interface UsernameSearchState {
    data object Idle : UsernameSearchState
    data object Loading : UsernameSearchState
    data class Found(val result: DirectoryLookupResult) : UsernameSearchState
    data class NotFound(val message: String = "User not found") : UsernameSearchState
}

@OptIn(ExperimentalCoroutinesApi::class)
class NomiChatViewModel(
    private val repository: NomiDataSource,
    private val smartReplyRepository: SmartReplyRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val currentUserId: String,
    networkObserver: NetworkConnectivityObserver? = null
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
            if (profile?.accountStatus == "banned") {
                _isBannedAccount.value = true
                _popupMessage.value = "Your account is banned."
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
                    .filter { !it.blocked && !it.archived }
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
            state.data.firstOrNull { it.id == activeId }
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

    init {
        ensureInitialSetup()
        observeAutoSmartSuggestions()
        startIncomingSyncLoop()
        observeIncomingMessageAlerts()
        observeNetworkRecovery()
        startStoryExpiryLoop()
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

    fun reconnectCloudSync() {
        viewModelScope.launch {
            if (!networkStatus.value.isConnected) {
                _snackbarMessage.value = "No internet connection. Waiting for Wi-Fi or mobile data."
                return@launch
            }
            repository.onNetworkReconnected()
            ensureInitialSetup()
            startIncomingSyncLoop()
            _snackbarMessage.value = "Cloud sync active (${networkStatus.value.transportLabel})"
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
                        _popupMessage.value = "Your account is banned."
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

        viewModelScope.launch {
            _quickSuggestions.value = emptyList()
            val result = repository.sendMessage(
                userId = currentUserId,
                contactId = contact.id,
                sender = "me",
                text = trimmed,
                aiGenerated = false
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
        viewModelScope.launch {
            _quickSuggestions.value = emptyList()
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

    fun reportActiveContact() {
        val contact = activeContact.value ?: return
        reportContact(contact)
    }

    fun reportContact(contact: ChatThread) {
        val myHandle = (profileState.value as? UiState.Success)?.data?.username.orEmpty()
            .ifBlank { currentUserId }

        viewModelScope.launch {
            repository.reportUserAccount(
                userId = currentUserId,
                contact = contact,
                reporterUsername = myHandle
            ).onSuccess { outcome ->
                _popupMessage.value = outcome.message
            }.onFailure { err ->
                _popupMessage.value = err.localizedMessage ?: "Unable to submit report."
            }
        }
    }

    fun findUserByUsername(username: String) {
        val clean = com.example.data.repository.NomiChatRepository.exactUsernameOrNull(username)
        if (clean == null) {
            _usernameSearchState.value = UsernameSearchState.NotFound("User not found")
            return
        }
        val myHandle = (profileState.value as? UiState.Success)?.data?.username.orEmpty()
        viewModelScope.launch {
            _usernameSearchState.value = UsernameSearchState.Loading
            repository.lookupUserByUsername(clean, viewerUserId = currentUserId)
                .onSuccess { found ->
                    _usernameSearchState.value = when {
                        found == null || !found.username.equals(clean, ignoreCase = true) ->
                            UsernameSearchState.NotFound("User not found")
                        found.ownerUid == currentUserId ||
                            (myHandle.isNotBlank() && found.username.equals(myHandle, ignoreCase = true)) ->
                            UsernameSearchState.NotFound("You cannot add your own username.")
                        else -> UsernameSearchState.Found(found)
                    }
                }
                .onFailure {
                    _usernameSearchState.value = UsernameSearchState.NotFound("User not found")
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
        val clean = com.example.data.repository.NomiChatRepository.exactUsernameOrNull(username)
        if (clean == null) {
            _usernameSearchState.value = UsernameSearchState.NotFound("User not found")
            return
        }
        val myHandle = (profileState.value as? UiState.Success)?.data?.username.orEmpty()
        viewModelScope.launch {
            _usernameSearchState.value = UsernameSearchState.Loading
            val lookupResult = repository.lookupUserByUsername(clean, viewerUserId = currentUserId)
            val found = lookupResult.getOrNull()
            if (lookupResult.isFailure || found == null || !found.username.equals(clean, ignoreCase = true)) {
                _usernameSearchState.value = UsernameSearchState.NotFound("User not found")
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
                _popupMessage.value = "Chat added"
                onSuccess()
            }.onFailure { err ->
                _usernameSearchState.value = UsernameSearchState.NotFound(
                    err.localizedMessage?.takeIf { it.isNotBlank() } ?: "User not found"
                )
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
        dob: String? = null
    ) {
        viewModelScope.launch {
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
                _snackbarMessage.value = "Profile updated"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Failed to update profile."
            }
        }
    }

    fun updateProfilePhotoFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
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
        }
    }

    fun updateProfileBannerFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
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
        }
    }

    fun updatePrivacySettings(
        visibility: ProfileVisibilityMode,
        allowedViewerIds: List<String>,
        maxViewers: Long
    ) {
        viewModelScope.launch {
            repository.updatePrivacySettings(
                userId = currentUserId,
                profileVisibility = visibility.key,
                allowedViewerUids = allowedViewerIds,
                maxProfileViewers = maxViewers
            ).onSuccess {
                _popupMessage.value = "Privacy settings updated"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "Unable to update privacy settings."
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
        if (_isPostingStory.value) return
        val cleanCaption = caption.trim()
        if (uri == null && cleanCaption.isEmpty()) {
            _snackbarMessage.value = "Choose a photo or enter story text."
            return
        }
        viewModelScope.launch {
            _isPostingStory.value = true
            try {
                val encoded = if (uri != null) {
                    withContext(Dispatchers.IO) {
                        encodeStoryMediaDataUri(context, uri)
                    }
                } else {
                    "text_card:emerald"
                }
                if (encoded == null) {
                    _snackbarMessage.value = "Unable to process selected image."
                    return@launch
                }
                repository.postStory(
                    userId = currentUserId,
                    mediaData = encoded,
                    caption = cleanCaption,
                    storyFont = StoryFontStyle.fromKey(storyFont).key
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

    fun clearAuthRejection() {
        _authRejectionMessage.value = null
    }

    private fun encodeAvatarDataUri(context: Context, uri: Uri): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = input.use { BitmapFactory.decodeStream(it) } ?: return null
            var maxDim = 320
            var dataUri: String? = null
            while (maxDim >= 160 && dataUri == null) {
                val scale = maxDim.toFloat() / max(original.width, original.height).toFloat()
                val targetW = if (scale < 1f) (original.width * scale).toInt().coerceAtLeast(1) else original.width
                val targetH = if (scale < 1f) (original.height * scale).toInt().coerceAtLeast(1) else original.height
                val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)
                var quality = 82
                while (quality >= 50) {
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val candidate = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    if (candidate.length <= 140_000) {
                        dataUri = candidate
                        break
                    }
                    quality -= 10
                }
                maxDim = (maxDim * 3) / 4
            }
            dataUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode avatar image", e)
            null
        }
    }

    private fun encodeStoryMediaDataUri(context: Context, uri: Uri): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = input.use { BitmapFactory.decodeStream(it) } ?: return null
            encodeBitmapToDataUri(original)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode story media", e)
            null
        }
    }

    private fun encodeBitmapToDataUri(original: Bitmap): String? {
        return try {
            var maxDim = 720
            var quality = 74
            var dataUri: String? = null
            while (maxDim >= 320) {
                val scale = maxDim.toFloat() / max(original.width, original.height).toFloat()
                val targetW = if (scale < 1f) (original.width * scale).toInt().coerceAtLeast(1) else original.width
                val targetH = if (scale < 1f) (original.height * scale).toInt().coerceAtLeast(1) else original.height
                val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)
                while (quality >= 45) {
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val candidate = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    if (candidate.length <= 220_000) {
                        dataUri = candidate
                        break
                    }
                    quality -= 12
                }
                if (dataUri != null) break
                maxDim = (maxDim * 3) / 4
                quality = 68
            }
            dataUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode bitmap media", e)
            null
        }
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
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = input.use { BitmapFactory.decodeStream(it) } ?: return null
            var maxDim = 720
            var dataUri: String? = null
            while (maxDim >= 280 && dataUri == null) {
                val scale = maxDim.toFloat() / max(original.width, original.height).toFloat()
                val targetW = if (scale < 1f) (original.width * scale).toInt().coerceAtLeast(1) else original.width
                val targetH = if (scale < 1f) (original.height * scale).toInt().coerceAtLeast(1) else original.height
                val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)
                var quality = 80
                while (quality >= 42) {
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val candidate = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    if (candidate.length <= 140_000) {
                        dataUri = candidate
                        break
                    }
                    quality -= 10
                }
                maxDim = (maxDim * 3) / 4
            }
            dataUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode banner image", e)
            null
        }
    }

    private companion object {
        const val TAG = "NomiChatVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
        const val PRESENCE_HEARTBEAT_MS = 60_000L
        const val STORY_EXPIRY_CHECK_MS = 30_000L
    }
}

package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
                    val matchesTab = when (filter) {
                        ChatPillFilter.GROUPS -> thread.threadType == "group"
                        else -> true
                    }
                    val matchesQuery = q.isEmpty() ||
                        thread.name.lowercase().contains(q) ||
                        thread.username.lowercase().contains(q) ||
                        thread.phone.lowercase().contains(q)
                    matchesTab && matchesQuery
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

    val totalUnreadCount: StateFlow<Int> = allContactsState
        .map { state ->
            when (state) {
                is UiState.Success -> state.data
                    .filter { !it.blocked }
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

    init {
        ensureInitialSetup()
        observeAutoSmartSuggestions()
        startIncomingSyncLoop()
        observeIncomingMessageAlerts()
        observeNetworkRecovery()
    }

    private fun startIncomingSyncLoop() {
        incomingSyncJob?.cancel()
        incomingSyncJob = viewModelScope.launch {
            repository.startIncomingSync(currentUserId) { _activeContactId.value }
        }
    }

    private fun observeIncomingMessageAlerts() {
        viewModelScope.launch {
            repository.incomingAlerts.collect { alert ->
                val matchesUser = alert.recipientUserId.isEmpty() || alert.recipientUserId == currentUserId
                if (!matchesUser) return@collect
                if (!preferences.value.notificationsEnabled) return@collect
                val viewingSameThreadInForeground = isAppInForeground && _activeContactId.value == alert.contactId
                if (!viewingSameThreadInForeground) {
                    _incomingNotificationEvents.tryEmit(alert)
                }
            }
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        presenceHeartbeatJob?.cancel()
        if (inForeground) {
            presenceHeartbeatJob = viewModelScope.launch {
                repository.setUserPresence(currentUserId, true)
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
                        generateThreeSmartReplies(contact, msgState.data)
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

    fun requestQuickSuggestions() {
        val contact = activeContact.value ?: return
        val history = (activeMessagesState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            generateThreeSmartReplies(contact, history)
        }
    }

    fun dismissQuickSuggestions() {
        _quickSuggestions.value = emptyList()
    }

    private suspend fun generateThreeSmartReplies(contact: ChatThread, history: List<ChatMessage>) {
        if (_isGeneratingSuggestions.value) return
        _isGeneratingSuggestions.value = true
        val myName = (profileState.value as? UiState.Success)?.data?.displayName ?: "Friend"

        smartReplyRepository.generateQuickSuggestions(
            userName = myName,
            contactName = contact.name,
            recentMessages = history
        ).onSuccess { chips ->
            _quickSuggestions.value = chips.take(3)
        }.onFailure { err ->
            _snackbarMessage.value = "Could not load smart suggestions: ${err.localizedMessage ?: "Try again"}"
        }
        _isGeneratingSuggestions.value = false
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
            repository.lookupUserByUsername(clean)
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
            val lookupResult = repository.lookupUserByUsername(clean)
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
            val maxDim = 220
            val scale = maxDim.toFloat() / max(original.width, original.height).toFloat()
            val targetW = if (scale < 1f) (original.width * scale).toInt().coerceAtLeast(1) else original.width
            val targetH = if (scale < 1f) (original.height * scale).toInt().coerceAtLeast(1) else original.height
            val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            val bytes = baos.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode avatar image", e)
            null
        }
    }

    private companion object {
        const val TAG = "NomiChatVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
        const val PRESENCE_HEARTBEAT_MS = 60_000L
    }
}

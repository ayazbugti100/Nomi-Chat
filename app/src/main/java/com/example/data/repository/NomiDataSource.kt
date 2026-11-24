package com.example.data.repository

import com.example.data.model.AuthFlowMode
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.ReportOutcome
import com.example.data.model.StoryFontStyle
import com.example.data.model.StoryItem
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.model.UserSetupOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface NomiDataSource {
    val requiresInitialCloudSetup: Boolean
    val incomingAlerts: Flow<IncomingMessageAlert> get() = emptyFlow()

    fun observeUserProfile(userId: String): Flow<UserProfile?>

    fun observeContacts(userId: String): Flow<List<ChatThread>>

    fun observeMessages(userId: String, contactId: String): Flow<List<ChatMessage>>

    fun observeStories(userId: String): Flow<List<StoryItem>> = emptyFlow()

    suspend fun postStory(
        userId: String,
        mediaData: String,
        caption: String = "",
        storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key
    ): Result<String> = Result.success("")

    suspend fun deleteStory(
        userId: String,
        storyId: String
    ): Result<Unit> = Result.success(Unit)

    suspend fun purgeExpiredStories(userId: String) {}

    suspend fun ensureInitialUserSetup(
        userId: String,
        googleDisplayName: String?,
        googleEmail: String?,
        preferredDisplayName: String,
        preferredUsername: String,
        preferredDob: String,
        authFlowMode: AuthFlowMode,
        returningIdentifier: String,
        pendingPassword: String = ""
    ): Result<UserSetupOutcome> = Result.success(UserSetupOutcome.Allowed)

    suspend fun updateProfile(
        userId: String,
        displayName: String? = null,
        username: String? = null,
        bio: String? = null,
        dob: String? = null,
        photoData: String? = null,
        bannerData: String? = null
    ): Result<String?>

    suspend fun updatePrivacySettings(
        userId: String,
        profileVisibility: String,
        allowedViewerUids: List<String>,
        maxProfileViewers: Long
    ): Result<Unit> = Result.success(Unit)

    suspend fun syncAccountSettings(
        userId: String,
        themeMode: ThemeMode? = null,
        notificationsEnabled: Boolean? = null,
        smartAiEnabled: Boolean? = null,
        appLockPinHash: String? = null
    ) {}

    suspend fun lookupUserByUsername(
        username: String,
        viewerUserId: String = ""
    ): Result<DirectoryLookupResult?> = Result.success(null)

    suspend fun addContact(
        userId: String,
        name: String,
        username: String,
        phone: String,
        threadType: String = "chat",
        peerUid: String = "",
        isRegisteredPeer: Boolean = false
    ): Result<String>

    suspend fun setContactTyping(
        userId: String,
        contactId: String,
        isTyping: Boolean
    ): Result<Unit>

    suspend fun sendMessage(
        userId: String,
        contactId: String,
        sender: String,
        text: String,
        aiGenerated: Boolean = false,
        incrementUnreadIfThem: Boolean = true,
        messageType: String = "text",
        mediaData: String? = null,
        durationSec: Long = 0L
    ): Result<Unit>

    suspend fun startIncomingSync(userId: String, activeContactIdProvider: () -> String?) {}

    suspend fun setUserPresence(userId: String, online: Boolean) {}

    suspend fun onNetworkReconnected() {}

    suspend fun markThreadRead(userId: String, contactId: String): Result<Unit>

    suspend fun toggleLockChat(userId: String, contactId: String, locked: Boolean): Result<Unit>

    suspend fun toggleBlockChat(userId: String, contactId: String, blocked: Boolean): Result<Unit>

    suspend fun toggleArchiveChat(userId: String, contactId: String, archived: Boolean): Result<Unit> =
        Result.success(Unit)

    suspend fun toggleMuteChat(userId: String, contactId: String, muted: Boolean): Result<Unit> =
        Result.success(Unit)

    suspend fun setDisappearingMessages(userId: String, contactId: String, seconds: Long): Result<Unit> =
        Result.success(Unit)

    suspend fun setChatTheme(userId: String, contactId: String, themeKey: String): Result<Unit> =
        Result.success(Unit)

    suspend fun clearChatMessages(userId: String, contactId: String): Result<Unit> =
        Result.success(Unit)

    suspend fun renameChat(userId: String, contactId: String, newName: String): Result<Unit> =
        Result.success(Unit)

    suspend fun deleteChatPermanently(userId: String, contactId: String): Result<Unit>

    suspend fun reportUserAccount(
        userId: String,
        contact: ChatThread,
        reporterUsername: String
    ): Result<ReportOutcome>
}


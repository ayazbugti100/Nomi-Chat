package com.example.data.repository

import com.example.data.model.AuthFlowMode
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.ReportOutcome
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.model.UserSetupOutcome
import kotlinx.coroutines.flow.Flow

interface NomiDataSource {
    val requiresInitialCloudSetup: Boolean

    fun observeUserProfile(userId: String): Flow<UserProfile?>

    fun observeContacts(userId: String): Flow<List<ChatThread>>

    fun observeMessages(userId: String, contactId: String): Flow<List<ChatMessage>>

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
        photoData: String? = null
    ): Result<String?>

    suspend fun syncAccountSettings(
        userId: String,
        themeMode: ThemeMode? = null,
        notificationsEnabled: Boolean? = null,
        smartAiEnabled: Boolean? = null,
        appLockPinHash: String? = null
    ) {}

    suspend fun lookupUserByUsername(username: String): Result<DirectoryLookupResult?> =
        Result.success(null)

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
        incrementUnreadIfThem: Boolean = true
    ): Result<Unit>

    suspend fun startIncomingSync(userId: String, activeContactIdProvider: () -> String?) {}

    suspend fun onNetworkReconnected() {}

    suspend fun markThreadRead(userId: String, contactId: String): Result<Unit>

    suspend fun toggleLockChat(userId: String, contactId: String, locked: Boolean): Result<Unit>

    suspend fun toggleBlockChat(userId: String, contactId: String, blocked: Boolean): Result<Unit>

    suspend fun deleteChatPermanently(userId: String, contactId: String): Result<Unit>

    suspend fun reportUserAccount(
        userId: String,
        contact: ChatThread,
        reporterUsername: String
    ): Result<ReportOutcome>
}


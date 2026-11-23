package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

const val BAN_REPORT_LIMIT = 10L

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val username: String = "",
    val bio: String = "Dream Big... 💚",
    val dob: String = "2000-01-01",
    val provider: String = "google",
    val photoData: String? = null,
    val online: Boolean = true,
    val verified: Boolean = true,
    val premiumUnlocked: Boolean = true,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val deletedContactIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserDirectoryEntry(
    @DocumentId val id: String = "",
    val ownerUid: String? = null,
    val username: String = "",
    val displayName: String = "",
    val provider: String = "google",
    val online: Boolean = false,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val reportedByUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatThread(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val username: String = "",
    val phone: String = "",
    val threadType: String = "chat",
    val online: Boolean = false,
    val typing: Boolean = false,
    val unreadCount: Long = 0L,
    val lastMessageText: String = "",
    val lastMessageFromMe: Boolean = false,
    val lastMessageTimeLabel: String = "",
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val blocked: Boolean = false,
    val peerUid: String = "",
    val isRegisteredPeer: Boolean = false,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val reportedBy: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class DirectoryLookupResult(
    val username: String,
    val displayName: String,
    val ownerUid: String = ""
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contactId: String = "",
    val sender: String = "me",
    val text: String = "",
    val timeLabel: String = "",
    val aiGenerated: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class ReportOutcome(
    val ok: Boolean,
    val message: String,
    val status: String = "active",
    val count: Long = 0L
)

data class IncomingMessageAlert(
    val contactId: String,
    val senderName: String,
    val senderHandle: String,
    val text: String,
    val recipientUserId: String = ""
)

enum class AuthFlowMode {
    SIGN_UP,
    RETURNING_LOGIN,
    GOOGLE_QUICK
}

sealed interface UserSetupOutcome {
    data object Allowed : UserSetupOutcome
    data object Banned : UserSetupOutcome
    data class Rejected(val message: String) : UserSetupOutcome
}

enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    AUTO("Auto")
}

enum class ChatPillFilter(val label: String) {
    CHATS("Chats"),
    GROUPS("Groups"),
    STATUS("Status"),
    CALLS("Calls")
}

enum class MainBottomTab {
    CHATS,
    GROUPS,
    CALLS,
    SETTINGS
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

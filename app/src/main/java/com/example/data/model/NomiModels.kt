package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

const val BAN_REPORT_LIMIT = 10L
const val STORY_DURATION_MS = 86_400_000L

enum class ProfileVisibilityMode(
    val key: String,
    val label: String,
    val shortBadge: String,
    val description: String
) {
    EVERYONE(
        key = "everyone",
        label = "Yes (Everyone)",
        shortBadge = "Yes",
        description = "Everyone can view your profile photo, banner, and bio."
    ),
    SELECTED(
        key = "selected",
        label = "Selected People",
        shortBadge = "Custom",
        description = "Only selected contacts (within your custom limit) can view your profile."
    ),
    NOBODY(
        key = "nobody",
        label = "No (Nobody)",
        shortBadge = "No",
        description = "Hide your profile photo, banner, and bio from all other users."
    );

    companion object {
        fun fromKey(key: String?): ProfileVisibilityMode {
            return entries.firstOrNull { it.key == key } ?: EVERYONE
        }
    }
}

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
    val bannerData: String? = null,
    val profileVisibility: String = ProfileVisibilityMode.EVERYONE.key,
    val allowedViewerUids: List<String> = emptyList(),
    val maxProfileViewers: Long = 0L,
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
    val bio: String = "",
    val photoData: String? = null,
    val bannerData: String? = null,
    val profileVisibility: String = ProfileVisibilityMode.EVERYONE.key,
    val allowedViewerUids: List<String> = emptyList(),
    val maxProfileViewers: Long = 0L,
    val online: Boolean = false,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val reportedByUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

enum class ChatThemeStyle(
    val key: String,
    val label: String
) {
    CLASSIC("classic", "Classic Doodle"),
    EMERALD("emerald", "Emerald Mint"),
    OCEAN("ocean", "Ocean Breeze"),
    MIDNIGHT("midnight", "Midnight Dark"),
    SAND("sand", "Warm Sand"),
    LAVENDER("lavender", "Lavender Mist");

    companion object {
        fun fromKey(key: String?): ChatThemeStyle {
            return entries.firstOrNull { it.key == key } ?: CLASSIC
        }
    }
}

@Keep
@IgnoreExtraProperties
data class ChatThread(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val username: String = "",
    val phone: String = "",
    val threadType: String = "chat",
    val bio: String = "",
    val photoData: String? = null,
    val bannerData: String? = null,
    val online: Boolean = false,
    val typing: Boolean = false,
    val unreadCount: Long = 0L,
    val lastMessageText: String = "",
    val lastMessageFromMe: Boolean = false,
    val lastMessageTimeLabel: String = "",
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val blocked: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val disappearingSeconds: Long = 0L,
    val chatTheme: String = ChatThemeStyle.CLASSIC.key,
    val customRenamed: Boolean = false,
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
    val ownerUid: String = "",
    val bio: String = "",
    val photoData: String? = null,
    val bannerData: String? = null
)

enum class StoryFontStyle(
    val key: String,
    val displayName: String,
    val urduSample: String
) {
    NOTO_NASTALIQ("noto_nastaliq", "Nastaliq", "نستعلیق"),
    GULZAR("gulzar", "Gulzar", "گلزار"),
    AMIRI("amiri", "Amiri", "امیری"),
    SCHEHERAZADE("scheherazade", "Scheherazade", "شہرزاد"),
    LATEEF("lateef", "Lateef", "لطیف"),
    CAIRO("cairo", "Cairo", "قاہرہ"),
    VAZIRMATN("vazirmatn", "Vazir", "وزیر"),
    IBM_PLEX("ibm_plex", "Plex", "پلیکس"),
    EL_MESSIRI("el_messiri", "Messiri", "المسیری"),
    MIRZA("mirza", "Mirza", "مرزا");

    companion object {
        fun fromKey(key: String?): StoryFontStyle {
            return entries.firstOrNull { it.key == key } ?: NOTO_NASTALIQ
        }
    }
}

@Keep
@IgnoreExtraProperties
data class StoryItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val authorName: String = "",
    val authorUsername: String = "",
    val authorPhotoData: String? = null,
    val mediaData: String = "",
    val caption: String = "",
    val storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key,
    val createdAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contactId: String = "",
    val sender: String = "me",
    val text: String = "",
    val messageType: String = "text",
    val mediaData: String? = null,
    val durationSec: Long = 0L,
    val expiresAtMs: Long = 0L,
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

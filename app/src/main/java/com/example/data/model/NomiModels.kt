package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

const val BAN_REPORT_LIMIT = 10L
const val STORY_DURATION_MS = 86_400_000L
const val ADMIN_GMAIL = "ayazbugti100@gmail.com"

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
    val email: String = "",
    val bio: String = "Dream Big... 💚",
    val dob: String = "2000-01-01",
    val provider: String = "google",
    val photoData: String? = null,
    val bannerData: String? = null,
    val profileVisibility: String = ProfileVisibilityMode.EVERYONE.key,
    val allowedViewerUids: List<String> = emptyList(),
    val maxProfileViewers: Long = 0L,
    val online: Boolean = true,
    val showOnlineStatus: Boolean = true,
    val verified: Boolean = false,
    val premiumUnlocked: Boolean = true,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val bannedByAdmin: Boolean = false,
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
    val showOnlineStatus: Boolean = true,
    val verified: Boolean = false,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val reportedByUids: List<String> = emptyList(),
    val bannedByAdmin: Boolean = false,
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
    val verified: Boolean = false,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val reportedBy: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isBanned: Boolean get() = accountStatus == "banned"
}

data class DirectoryLookupResult(
    val username: String,
    val displayName: String,
    val ownerUid: String = "",
    val bio: String = "",
    val photoData: String? = null,
    val bannerData: String? = null,
    val verified: Boolean = false,
    val accountStatus: String = "active"
) {
    val isBanned: Boolean get() = accountStatus == "banned"
}

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
    val mediaType: String = "image",
    val caption: String = "",
    val storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key,
    val musicTag: String = "",
    val overlayText: String = "",
    val overlayColorHex: Long = 0xFFFFFFFFL,
    val overlayYFraction: Double = 0.5,
    val stickersJson: String = "",
    val drawingJson: String = "",
    val cropMode: String = "crop",
    val rotationDegrees: Long = 0L,
    val viewedByUids: List<String> = emptyList(),
    val likedByUids: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StoryViewRecord(
    @DocumentId val id: String = "",
    val viewerUid: String = "",
    val viewerName: String = "",
    val viewerUsername: String = "",
    val viewerPhotoData: String? = null,
    val liked: Boolean = false,
    val reactionEmoji: String = "",
    val viewedAtMs: Long = 0L,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class StoryStickerOverlay(
    val id: String,
    val text: String,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.45f
)

data class StoryDrawStroke(
    val colorHex: Long,
    val strokeWidth: Float = 8f,
    val points: List<Pair<Float, Float>>
)

object StoryOverlayCodec {
    fun encodeStickers(stickers: List<StoryStickerOverlay>): String {
        if (stickers.isEmpty()) return ""
        val arr = org.json.JSONArray()
        stickers.take(20).forEach { s ->
            arr.put(
                org.json.JSONObject().apply {
                    put("id", s.id)
                    put("text", s.text)
                    put("x", s.xFraction.toDouble())
                    put("y", s.yFraction.toDouble())
                }
            )
        }
        return arr.toString()
    }

    fun decodeStickers(json: String): List<StoryStickerOverlay> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val text = obj.optString("text").takeIf { it.isNotBlank() } ?: continue
                    add(
                        StoryStickerOverlay(
                            id = obj.optString("id", "st_$i"),
                            text = text,
                            xFraction = obj.optDouble("x", 0.5).toFloat().coerceIn(0.08f, 0.92f),
                            yFraction = obj.optDouble("y", 0.45).toFloat().coerceIn(0.12f, 0.85f)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeStrokes(strokes: List<StoryDrawStroke>): String {
        if (strokes.isEmpty()) return ""
        val arr = org.json.JSONArray()
        strokes.take(60).forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val ptsArr = org.json.JSONArray()
            // Downsample slightly if very dense to keep payload compact
            val step = if (stroke.points.size > 60) 2 else 1
            stroke.points.forEachIndexed { idx, (x, y) ->
                if (idx % step == 0 || idx == stroke.points.lastIndex) {
                    ptsArr.put(
                        org.json.JSONArray().apply {
                            put((x * 1000f).toInt() / 1000.0)
                            put((y * 1000f).toInt() / 1000.0)
                        }
                    )
                }
            }
            arr.put(
                org.json.JSONObject().apply {
                    put("c", stroke.colorHex)
                    put("w", stroke.strokeWidth.toDouble())
                    put("p", ptsArr)
                }
            )
        }
        return arr.toString().take(23500)
    }

    fun decodeStrokes(json: String): List<StoryDrawStroke> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ptsArr = obj.optJSONArray("p") ?: continue
                    val pts = buildList {
                        for (j in 0 until ptsArr.length()) {
                            val pt = ptsArr.optJSONArray(j) ?: continue
                            if (pt.length() >= 2) {
                                add(pt.optDouble(0).toFloat() to pt.optDouble(1).toFloat())
                            }
                        }
                    }
                    if (pts.isNotEmpty()) {
                        add(
                            StoryDrawStroke(
                                colorHex = obj.optLong("c", 0xFFFFFFFFL),
                                strokeWidth = obj.optDouble("w", 8.0).toFloat(),
                                points = pts
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}

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
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val replyToMessageId: String? = null,
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

@Keep
@IgnoreExtraProperties
data class ModerationAlert(
    @DocumentId val id: String = "",
    val reportedUid: String = "",
    val reportedUsername: String = "",
    val reportedDisplayName: String = "",
    val reporterUid: String = "",
    val reporterUsername: String = "",
    val reporterEmail: String = "",
    val reason: String = "Other",
    val status: String = "pending",
    val adminEmail: String = "",
    val createdAtMs: Long = 0L,
    val resolvedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AdminLockConfig(
    @DocumentId val id: String = "",
    val lockedGmail: String = ADMIN_GMAIL,
    val adminUid: String = "",
    val enabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AdminUserRecord(
    @DocumentId val uid: String = "",
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val bio: String = "",
    val photoData: String? = null,
    val online: Boolean = false,
    val verified: Boolean = false,
    val accountStatus: String = "active",
    val reportCount: Long = 0L,
    val bannedByAdmin: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isBanned: Boolean get() = accountStatus == "banned" || bannedByAdmin
}

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

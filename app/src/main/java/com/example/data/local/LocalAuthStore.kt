package com.example.data.local

import android.content.Context
import com.example.data.model.BAN_REPORT_LIMIT
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.ProfileVisibilityMode
import com.example.data.model.ReportOutcome
import com.example.data.model.STORY_DURATION_MS
import com.example.data.model.StoryFontStyle
import com.example.data.model.StoryItem
import com.example.data.model.StoryViewRecord
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.repository.NomiChatRepository
import com.example.data.repository.NomiDataSource
import com.example.data.security.PasswordHasher
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface LocalAuthOutcome {
    data class Success(val username: String) : LocalAuthOutcome
    data class Error(val message: String) : LocalAuthOutcome
    data class Banned(val message: String = "Your account is banned.") : LocalAuthOutcome
}

data class LocalAccountSettings(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val notificationsEnabled: Boolean = true,
    val smartAiEnabled: Boolean = true,
    val appLockPinHash: String = ""
)

class LocalAuthStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "nomi_auth_users.json")
    private val retiredUsernamesFile = File(context.applicationContext.filesDir, "nomi_retired_usernames.json")
    private val mutex = Mutex()
    private val changeTick = MutableStateFlow(0L)
    private val _incomingAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 24)
    val incomingAlerts: Flow<IncomingMessageAlert> = _incomingAlerts.asSharedFlow()

    private fun readRootUnlocked(): JSONObject {
        if (!file.exists()) return JSONObject()
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }.getOrElse { JSONObject() }
    }

    private fun writeRootUnlocked(root: JSONObject) {
        file.writeText(root.toString())
        changeTick.value = changeTick.value + 1L
    }

    private fun readRetiredUsernamesUnlocked(): MutableSet<String> {
        if (!retiredUsernamesFile.exists()) return mutableSetOf()
        return runCatching {
            val raw = retiredUsernamesFile.readText()
            if (raw.isBlank()) return@runCatching mutableSetOf<String>()
            val arr = JSONArray(raw)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val item = NomiChatRepository.normalizeUsername(arr.optString(i))
                if (item.isNotBlank()) set.add(item)
            }
            set
        }.getOrElse { mutableSetOf() }
    }

    private fun retireUsernameUnlocked(username: String) {
        val clean = NomiChatRepository.normalizeUsername(username).take(40)
        if (clean.isBlank()) return
        val retired = readRetiredUsernamesUnlocked()
        if (retired.add(clean)) {
            val arr = JSONArray()
            retired.forEach { arr.put(it) }
            retiredUsernamesFile.writeText(arr.toString())
        }
    }

    suspend fun signUpUser(
        fullName: String,
        usernameInput: String,
        dob: String,
        password: String,
        rePassword: String
    ): LocalAuthOutcome = withContext(Dispatchers.IO) {
        val name = fullName.trim().take(60)
        val user = NomiChatRepository.normalizeUsername(usernameInput).take(40)
        val birthDate = dob.trim().take(20)

        if (name.isEmpty() || user.isEmpty() || birthDate.isEmpty() || password.isEmpty() || rePassword.isEmpty()) {
            return@withContext LocalAuthOutcome.Error("Please fill in all fields.")
        }
        if (password != rePassword) {
            return@withContext LocalAuthOutcome.Error("Passwords do not match.")
        }
        if (user.length < 2) {
            return@withContext LocalAuthOutcome.Error("Username must be at least 2 characters.")
        }

        mutex.withLock {
            val root = readRootUnlocked()
            val retired = readRetiredUsernamesUnlocked()
            if (retired.contains(user) || findAccountKeyByUsernameUnlocked(root, user) != null) {
                return@withContext LocalAuthOutcome.Error("This username is already taken / already added.")
            }

            val salt = PasswordHasher.generateSalt()
            val hash = PasswordHasher.hashPassword(password, salt)
            val nowSec = System.currentTimeMillis() / 1000L

            val accountJson = JSONObject().apply {
                put("userId", user)
                put("displayName", name)
                put("username", user)
                put("bio", "Dream Big... 💚")
                put("dob", birthDate)
                put("provider", "username")
                put("photoData", JSONObject.NULL)
                put("bannerData", JSONObject.NULL)
                put("profileVisibility", ProfileVisibilityMode.EVERYONE.key)
                put("allowedViewerUids", JSONArray())
                put("maxProfileViewers", 0L)
                put("online", true)
                put("showOnlineStatus", true)
                put("passwordSalt", salt)
                put("passwordHash", hash)
                put("accountStatus", "active")
                put("reportCount", 0L)
                put("createdAtSec", nowSec)
                put("updatedAtSec", nowSec)
                put(
                    "settings",
                    JSONObject().apply {
                        put("themeMode", ThemeMode.LIGHT.name)
                        put("notificationsEnabled", true)
                        put("smartAiEnabled", true)
                        put("appLockPinHash", "")
                    }
                )
                put("contacts", JSONArray())
                put("messages", JSONObject())
                put("deletedIds", JSONArray())
                put("stories", JSONArray())
            }

            root.put(user, accountJson)
            writeRootUnlocked(root)
            LocalAuthOutcome.Success(user)
        }
    }

    suspend fun returningLogin(
        usernameInput: String,
        password: String
    ): LocalAuthOutcome = withContext(Dispatchers.IO) {
        val user = NomiChatRepository.normalizeUsername(usernameInput).take(40)
        if (user.isEmpty() || password.isEmpty()) {
            return@withContext LocalAuthOutcome.Error("Please enter both username and password.")
        }

        mutex.withLock {
            val root = readRootUnlocked()
            val retired = readRetiredUsernamesUnlocked()
            if (retired.contains(user)) {
                return@withContext LocalAuthOutcome.Error("Account Not Found")
            }
            val accountKey = findAccountKeyByUsernameUnlocked(root, user)
                ?: return@withContext LocalAuthOutcome.Error("Account Not Found")
            val account = root.optJSONObject(accountKey)
                ?: return@withContext LocalAuthOutcome.Error("Account Not Found")
            val salt = account.optString("passwordSalt", "")
            val expectedHash = account.optString("passwordHash", "")
            if (!PasswordHasher.verifyPassword(password, salt, expectedHash)) {
                return@withContext LocalAuthOutcome.Error("Invalid username or password.")
            }
            if (account.optString("accountStatus", "active") == "banned") {
                return@withContext LocalAuthOutcome.Banned("Your account is banned.")
            }
            LocalAuthOutcome.Success(accountKey)
        }
    }

    suspend fun validateActiveSession(activeUsername: String): LocalAuthOutcome = withContext(Dispatchers.IO) {
        val cleanUser = NomiChatRepository.normalizeUsername(activeUsername).take(40)
        if (cleanUser.isEmpty()) {
            return@withContext LocalAuthOutcome.Error("No active session.")
        }
        mutex.withLock {
            val root = readRootUnlocked()
            val accountKey = findAccountKeyByUsernameUnlocked(root, cleanUser)
                ?: return@withContext LocalAuthOutcome.Error("User not found.")
            val account = root.optJSONObject(accountKey)
                ?: return@withContext LocalAuthOutcome.Error("User not found.")
            if (account.optString("accountStatus", "active") == "banned") {
                return@withContext LocalAuthOutcome.Banned("Your account is banned.")
            }
            LocalAuthOutcome.Success(accountKey)
        }
    }

    suspend fun getAccountSettings(username: String): LocalAccountSettings? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRootUnlocked()
            val accountKey = findAccountKeyByUsernameUnlocked(root, username) ?: return@withLock null
            val account = root.optJSONObject(accountKey) ?: return@withLock null
            val settingsObj = account.optJSONObject("settings") ?: return@withLock LocalAccountSettings()
            val themeRaw = settingsObj.optString("themeMode", ThemeMode.LIGHT.name)
            val themeMode = ThemeMode.entries.firstOrNull { it.name == themeRaw } ?: ThemeMode.LIGHT
            LocalAccountSettings(
                themeMode = themeMode,
                notificationsEnabled = settingsObj.optBoolean("notificationsEnabled", true),
                smartAiEnabled = settingsObj.optBoolean("smartAiEnabled", true),
                appLockPinHash = settingsObj.optString("appLockPinHash", "")
            )
        }
    }

    suspend fun updateAccountSettings(
        usernameKey: String,
        themeMode: ThemeMode? = null,
        notificationsEnabled: Boolean? = null,
        smartAiEnabled: Boolean? = null,
        appLockPinHash: String? = null
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRootUnlocked()
            val accountKey = findAccountKeyByUsernameUnlocked(root, usernameKey) ?: return@withLock
            val account = root.optJSONObject(accountKey) ?: return@withLock
            val settingsObj = account.optJSONObject("settings") ?: JSONObject()
            themeMode?.let { settingsObj.put("themeMode", it.name) }
            notificationsEnabled?.let { settingsObj.put("notificationsEnabled", it) }
            smartAiEnabled?.let { settingsObj.put("smartAiEnabled", it) }
            appLockPinHash?.let { settingsObj.put("appLockPinHash", it) }
            account.put("settings", settingsObj)
            account.put("updatedAtSec", System.currentTimeMillis() / 1000L)
            root.put(accountKey, account)
            writeRootUnlocked(root)
        }
    }

    private fun findAccountKeyByUsernameUnlocked(root: JSONObject, targetUsername: String): String? {
        if (root.has(targetUsername)) return targetUsername
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = root.optJSONObject(key) ?: continue
            if (obj.optString("username") == targetUsername) {
                return key
            }
        }
        return null
    }

    fun observeProfile(username: String): Flow<UserProfile?> = changeTick.map {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRootUnlocked()
                val obj = root.optJSONObject(username) ?: return@withLock null
                jsonToUserProfile(obj)
            }
        }
    }

    suspend fun setUserPresence(usernameKey: String, online: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRootUnlocked()
            val accountKey = findAccountKeyByUsernameUnlocked(root, usernameKey) ?: return@withLock
            val account = root.optJSONObject(accountKey) ?: return@withLock
            val showOnline = account.optBoolean("showOnlineStatus", true)
            val effectiveOnline = online && showOnline
            if (account.optBoolean("online", false) == effectiveOnline) return@withLock
            account.put("online", effectiveOnline)
            account.put("updatedAtSec", System.currentTimeMillis() / 1000L)
            root.put(accountKey, account)
            writeRootUnlocked(root)
        }
    }

    fun observeContacts(username: String): Flow<List<ChatThread>> = changeTick.map {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(username) ?: return@withLock emptyList()
                val deletedArr = account.optJSONArray("deletedIds") ?: JSONArray()
                val deletedSet = HashSet<String>(deletedArr.length())
                for (i in 0 until deletedArr.length()) {
                    deletedArr.optString(i).takeIf { it.isNotBlank() }?.let { deletedSet.add(it) }
                }
                val viewerHandle = account.optString("username", username)
                val arr = account.optJSONArray("contacts") ?: return@withLock emptyList()
                val list = mutableListOf<ChatThread>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isNotBlank() && !deletedSet.contains(id)) {
                        val thread = jsonToChatThread(obj)
                        val peerKey = thread.peerUid.ifBlank { thread.username }
                        val peerAccount = findAccountKeyByUsernameUnlocked(root, peerKey)?.let { root.optJSONObject(it) }
                        val realOnline = if (thread.threadType == "chat" && peerAccount != null) {
                            peerAccount.optString("accountStatus", "active") != "banned" &&
                                peerAccount.optBoolean("showOnlineStatus", true) &&
                                peerAccount.optBoolean("online", false)
                        } else {
                            false
                        }
                        val peerDisplayName = if (thread.threadType == "chat" && !thread.customRenamed && peerAccount != null) {
                            peerAccount.optString("displayName").trim().takeIf { it.isNotBlank() }
                        } else null
                        val visibleToViewer = if (thread.threadType == "chat" && peerAccount != null) {
                            canAccountShowProfileTo(peerAccount, username, viewerHandle)
                        } else true
                        val peerBio = if (!visibleToViewer) {
                            ""
                        } else if (thread.threadType == "chat" && peerAccount != null) {
                            peerAccount.optString("bio", "").take(200)
                        } else {
                            thread.bio
                        }
                        val peerPhoto = if (!visibleToViewer) {
                            null
                        } else if (thread.threadType == "chat" && peerAccount != null && !peerAccount.isNull("photoData")) {
                            peerAccount.optString("photoData").takeIf { it.isNotBlank() } ?: thread.photoData
                        } else {
                            thread.photoData
                        }
                        val peerBanner = if (!visibleToViewer) {
                            null
                        } else if (thread.threadType == "chat" && peerAccount != null && !peerAccount.isNull("bannerData")) {
                            peerAccount.optString("bannerData").takeIf { it.isNotBlank() } ?: thread.bannerData
                        } else {
                            thread.bannerData
                        }
                        list.add(
                            thread.copy(
                                name = peerDisplayName ?: thread.name,
                                bio = peerBio,
                                online = realOnline,
                                photoData = peerPhoto,
                                bannerData = peerBanner
                            )
                        )
                    }
                }
                list.sortedByDescending { it.updatedAt?.seconds ?: 0L }
            }
        }
    }

    fun observeStories(usernameKey: String): Flow<List<StoryItem>> = changeTick.map {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRootUnlocked()
                val nowMs = System.currentTimeMillis()
                val active = mutableListOf<StoryItem>()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val acc = root.optJSONObject(key) ?: continue
                    if (acc.optString("accountStatus", "active") == "banned") continue
                    val accName = acc.optString("displayName", key)
                    val accHandle = acc.optString("username", key)
                    val accPhoto = if (acc.isNull("photoData")) null else acc.optString("photoData").takeIf { it.isNotBlank() }
                    val storiesArr = acc.optJSONArray("stories") ?: continue
                    for (i in 0 until storiesArr.length()) {
                        val sObj = storiesArr.optJSONObject(i) ?: continue
                        val exp = sObj.optLong("expiresAtMs", 0L)
                        if (exp > nowMs) {
                            active.add(
                                jsonToStoryItem(
                                    obj = sObj,
                                    fallbackName = accName,
                                    fallbackUsername = accHandle,
                                    fallbackPhoto = accPhoto
                                )
                            )
                        }
                    }
                }
                active.sortedByDescending { it.createdAtMs }
            }
        }
    }

    fun observeStoryViews(usernameKey: String, storyId: String): Flow<List<StoryViewRecord>> = changeTick.map {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRootUnlocked()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val acc = root.optJSONObject(key) ?: continue
                    val storiesArr = acc.optJSONArray("stories") ?: continue
                    for (i in 0 until storiesArr.length()) {
                        val sObj = storiesArr.optJSONObject(i) ?: continue
                        if (sObj.optString("id") == storyId) {
                            val viewsArr = sObj.optJSONArray("views") ?: return@withLock emptyList()
                            return@withLock buildList {
                                for (v in 0 until viewsArr.length()) {
                                    val vObj = viewsArr.optJSONObject(v) ?: continue
                                    val uid = vObj.optString("viewerUid")
                                    if (uid.isBlank()) continue
                                    val vAcc = root.optJSONObject(uid)
                                    val livePhoto = if (vAcc != null && !vAcc.isNull("photoData")) {
                                        vAcc.optString("photoData").takeIf { it.isNotBlank() }
                                    } else {
                                        if (vObj.isNull("viewerPhotoData")) null else vObj.optString("viewerPhotoData").takeIf { it.isNotBlank() }
                                    }
                                    val viewedAtMs = vObj.optLong("viewedAtMs", 0L)
                                    add(
                                        StoryViewRecord(
                                            id = uid,
                                            viewerUid = uid,
                                            viewerName = vAcc?.optString("displayName")?.ifBlank { null }
                                                ?: vObj.optString("viewerName", uid),
                                            viewerUsername = vAcc?.optString("username")?.ifBlank { null }
                                                ?: vObj.optString("viewerUsername", uid),
                                            viewerPhotoData = livePhoto,
                                            liked = vObj.optBoolean("liked", false),
                                            reactionEmoji = vObj.optString("reactionEmoji", ""),
                                            viewedAtMs = viewedAtMs,
                                            updatedAt = Timestamp(viewedAtMs / 1000L, 0)
                                        )
                                    )
                                }
                            }.sortedByDescending { it.viewedAtMs }
                        }
                    }
                }
                emptyList()
            }
        }
    }

    suspend fun postStory(
        usernameKey: String,
        mediaData: String,
        caption: String = "",
        storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key,
        mediaType: String = "image",
        musicTag: String = "",
        overlayText: String = "",
        overlayColorHex: Long = 0xFFFFFFFFL,
        overlayYFraction: Double = 0.5,
        stickersJson: String = "",
        drawingJson: String = "",
        cropMode: String = "crop",
        rotationDegrees: Long = 0L,
        createdAtMs: Long = System.currentTimeMillis()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(mediaData.isNotBlank() && mediaData.length <= 920000) { "Story media is invalid or too large." }
            val cleanCaption = caption.trim().take(140)
            val normalizedFont = StoryFontStyle.fromKey(storyFont).key
            val cleanMediaType = when (mediaType) {
                "video", "text" -> mediaType
                else -> "image"
            }
            val cleanCrop = if (cropMode == "fit") "fit" else "crop"
            val cleanRotation = when (rotationDegrees) {
                90L, 180L, 270L -> rotationDegrees
                else -> 0L
            }
            mutex.withLock {
                val root = readRootUnlocked()
                val accountKey = findAccountKeyByUsernameUnlocked(root, usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val account = root.optJSONObject(accountKey)
                    ?: throw IllegalStateException("Account not found.")
                val storyId = "st_${UUID.randomUUID().toString().take(10)}"
                val expiresAtMs = createdAtMs + STORY_DURATION_MS
                val authorPhoto = if (account.isNull("photoData")) null else account.optString("photoData").takeIf { it.isNotBlank() }

                val existingStories = account.optJSONArray("stories") ?: JSONArray()
                val nowMs = System.currentTimeMillis()
                val keptStories = JSONArray()
                val newObj = JSONObject().apply {
                    put("id", storyId)
                    put("userId", accountKey)
                    put("authorName", account.optString("displayName", accountKey).take(60))
                    put("authorUsername", account.optString("username", accountKey).take(40))
                    if (authorPhoto != null) put("authorPhotoData", authorPhoto) else put("authorPhotoData", JSONObject.NULL)
                    put("mediaData", mediaData)
                    put("mediaType", cleanMediaType)
                    put("caption", cleanCaption)
                    put("storyFont", normalizedFont)
                    put("musicTag", musicTag.trim().take(80))
                    put("overlayText", overlayText.trim().take(140))
                    put("overlayColorHex", overlayColorHex)
                    put("overlayYFraction", overlayYFraction.coerceIn(0.0, 1.0))
                    put("stickersJson", stickersJson.take(4000))
                    put("drawingJson", drawingJson.take(24000))
                    put("cropMode", cleanCrop)
                    put("rotationDegrees", cleanRotation)
                    put("viewedByUids", JSONArray())
                    put("likedByUids", JSONArray())
                    put("views", JSONArray())
                    put("createdAtMs", createdAtMs)
                    put("expiresAtMs", expiresAtMs)
                }
                keptStories.put(newObj)
                for (i in 0 until existingStories.length()) {
                    val item = existingStories.optJSONObject(i) ?: continue
                    if (item.optLong("expiresAtMs", 0L) > nowMs) {
                        keptStories.put(item)
                    }
                }
                account.put("stories", keptStories)
                root.put(accountKey, account)
                writeRootUnlocked(root)
                storyId
            }
        }
    }

    suspend fun recordStoryView(viewerUsernameKey: String, storyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (storyId.isBlank()) return@runCatching
            mutex.withLock {
                val root = readRootUnlocked()
                val viewerKey = findAccountKeyByUsernameUnlocked(root, viewerUsernameKey) ?: return@runCatching
                val viewerAcc = root.optJSONObject(viewerKey) ?: return@runCatching
                val viewerName = viewerAcc.optString("displayName", viewerKey).take(60)
                val viewerHandle = viewerAcc.optString("username", viewerKey).take(40)
                val viewerPhoto = if (viewerAcc.isNull("photoData")) null else viewerAcc.optString("photoData").takeIf { it.isNotBlank() }

                val keys = root.keys()
                while (keys.hasNext()) {
                    val ownerKey = keys.next()
                    val ownerAcc = root.optJSONObject(ownerKey) ?: continue
                    val storiesArr = ownerAcc.optJSONArray("stories") ?: continue
                    for (i in 0 until storiesArr.length()) {
                        val sObj = storiesArr.optJSONObject(i) ?: continue
                        if (sObj.optString("id") == storyId) {
                            if (ownerKey == viewerKey) return@runCatching
                            val viewedArr = sObj.optJSONArray("viewedByUids") ?: JSONArray()
                            var alreadyInViewed = false
                            for (j in 0 until viewedArr.length()) {
                                if (viewedArr.optString(j) == viewerKey) {
                                    alreadyInViewed = true
                                    break
                                }
                            }
                            if (!alreadyInViewed) {
                                viewedArr.put(viewerKey)
                                sObj.put("viewedByUids", viewedArr)
                            }
                            val viewsArr = sObj.optJSONArray("views") ?: JSONArray()
                            var existingViewObj: JSONObject? = null
                            for (v in 0 until viewsArr.length()) {
                                val vObj = viewsArr.optJSONObject(v) ?: continue
                                if (vObj.optString("viewerUid") == viewerKey) {
                                    existingViewObj = vObj
                                    break
                                }
                            }
                            if (existingViewObj == null) {
                                viewsArr.put(
                                    JSONObject().apply {
                                        put("viewerUid", viewerKey)
                                        put("viewerName", viewerName)
                                        put("viewerUsername", viewerHandle)
                                        if (viewerPhoto != null) put("viewerPhotoData", viewerPhoto) else put("viewerPhotoData", JSONObject.NULL)
                                        put("liked", false)
                                        put("reactionEmoji", "")
                                        put("viewedAtMs", System.currentTimeMillis())
                                    }
                                )
                                sObj.put("views", viewsArr)
                            }
                            ownerAcc.put("stories", storiesArr)
                            root.put(ownerKey, ownerAcc)
                            writeRootUnlocked(root)
                            return@runCatching
                        }
                    }
                }
            }
        }
    }

    suspend fun reactToStory(
        viewerUsernameKey: String,
        storyId: String,
        liked: Boolean? = null,
        reactionEmoji: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (storyId.isBlank()) return@runCatching
            mutex.withLock {
                val root = readRootUnlocked()
                val viewerKey = findAccountKeyByUsernameUnlocked(root, viewerUsernameKey) ?: return@runCatching
                val viewerAcc = root.optJSONObject(viewerKey) ?: return@runCatching
                val viewerName = viewerAcc.optString("displayName", viewerKey).take(60)
                val viewerHandle = viewerAcc.optString("username", viewerKey).take(40)
                val viewerPhoto = if (viewerAcc.isNull("photoData")) null else viewerAcc.optString("photoData").takeIf { it.isNotBlank() }

                val keys = root.keys()
                while (keys.hasNext()) {
                    val ownerKey = keys.next()
                    val ownerAcc = root.optJSONObject(ownerKey) ?: continue
                    val storiesArr = ownerAcc.optJSONArray("stories") ?: continue
                    for (i in 0 until storiesArr.length()) {
                        val sObj = storiesArr.optJSONObject(i) ?: continue
                        if (sObj.optString("id") == storyId) {
                            val viewsArr = sObj.optJSONArray("views") ?: JSONArray()
                            var existingViewObj: JSONObject? = null
                            for (v in 0 until viewsArr.length()) {
                                val vObj = viewsArr.optJSONObject(v) ?: continue
                                if (vObj.optString("viewerUid") == viewerKey) {
                                    existingViewObj = vObj
                                    break
                                }
                            }
                            if (existingViewObj == null) {
                                existingViewObj = JSONObject().apply {
                                    put("viewerUid", viewerKey)
                                    put("viewerName", viewerName)
                                    put("viewerUsername", viewerHandle)
                                    if (viewerPhoto != null) put("viewerPhotoData", viewerPhoto) else put("viewerPhotoData", JSONObject.NULL)
                                    put("liked", liked ?: false)
                                    put("reactionEmoji", reactionEmoji.orEmpty().take(16))
                                    put("viewedAtMs", System.currentTimeMillis())
                                }
                                viewsArr.put(existingViewObj)
                            } else {
                                if (liked != null) existingViewObj.put("liked", liked)
                                if (reactionEmoji != null) existingViewObj.put("reactionEmoji", reactionEmoji.take(16))
                            }
                            sObj.put("views", viewsArr)

                            if (liked != null) {
                                val likedArr = sObj.optJSONArray("likedByUids") ?: JSONArray()
                                val nextLiked = JSONArray()
                                var found = false
                                for (k in 0 until likedArr.length()) {
                                    val uid = likedArr.optString(k)
                                    if (uid == viewerKey) {
                                        found = true
                                        if (liked) nextLiked.put(uid)
                                    } else if (uid.isNotBlank()) {
                                        nextLiked.put(uid)
                                    }
                                }
                                if (liked && !found) nextLiked.put(viewerKey)
                                sObj.put("likedByUids", nextLiked)
                            }
                            ownerAcc.put("stories", storiesArr)
                            root.put(ownerKey, ownerAcc)
                            writeRootUnlocked(root)
                            return@runCatching
                        }
                    }
                }
            }
        }
    }

    suspend fun replyToStory(
        usernameKey: String,
        story: StoryItem,
        replyText: String
    ): Result<Unit> {
        val cleanReply = replyText.trim().take(2000)
        if (cleanReply.isBlank()) return Result.success(Unit)
        val existingContactId = withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRootUnlocked()
                val accKey = findAccountKeyByUsernameUnlocked(root, usernameKey) ?: return@withLock null
                val acc = root.optJSONObject(accKey) ?: return@withLock null
                val contacts = acc.optJSONArray("contacts") ?: return@withLock null
                for (i in 0 until contacts.length()) {
                    val c = contacts.optJSONObject(i) ?: continue
                    if ((story.userId.isNotBlank() && c.optString("peerUid") == story.userId) ||
                        (story.authorUsername.isNotBlank() && c.optString("username").equals(story.authorUsername, ignoreCase = true))
                    ) {
                        return@withLock c.optString("id")
                    }
                }
                null
            }
        }
        val targetContactId = existingContactId ?: addContact(
            usernameKey = usernameKey,
            name = story.authorName.ifBlank { story.authorUsername },
            contactUsername = story.authorUsername,
            phone = "",
            threadType = "chat",
            peerUid = story.userId,
            isRegisteredPeer = story.userId.isNotBlank()
        ).getOrElse { return Result.failure(it) }

        val storySnippet = when {
            story.caption.isNotBlank() -> "Story: ${story.caption.take(80)}"
            story.overlayText.isNotBlank() -> "Story: ${story.overlayText.take(80)}"
            story.mediaType == "video" -> "Story: 🎬 Video"
            else -> "Story: 📷 Photo"
        }

        return sendMessage(
            usernameKey = usernameKey,
            contactId = targetContactId,
            sender = "me",
            text = cleanReply,
            aiGenerated = false,
            replyToText = storySnippet,
            replyToSender = story.authorName.ifBlank { story.authorUsername },
            replyToMessageId = story.id
        )
    }

    suspend fun deleteStory(usernameKey: String, storyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val accountKey = findAccountKeyByUsernameUnlocked(root, usernameKey) ?: return@withLock
                val account = root.optJSONObject(accountKey) ?: return@withLock
                val existingStories = account.optJSONArray("stories") ?: return@withLock
                val kept = JSONArray()
                var changed = false
                for (i in 0 until existingStories.length()) {
                    val item = existingStories.optJSONObject(i) ?: continue
                    if (item.optString("id") == storyId) {
                        changed = true
                    } else {
                        kept.put(item)
                    }
                }
                if (changed) {
                    account.put("stories", kept)
                    root.put(accountKey, account)
                    writeRootUnlocked(root)
                }
            }
        }
    }

    suspend fun purgeExpiredStories(usernameKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRootUnlocked()
            val nowMs = System.currentTimeMillis()
            var anyChanged = false
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val acc = root.optJSONObject(key) ?: continue
                val existingStories = acc.optJSONArray("stories") ?: continue
                val kept = JSONArray()
                var accChanged = false
                for (i in 0 until existingStories.length()) {
                    val item = existingStories.optJSONObject(i) ?: continue
                    if (item.optLong("expiresAtMs", 0L) > nowMs) {
                        kept.put(item)
                    } else {
                        accChanged = true
                    }
                }
                if (accChanged) {
                    acc.put("stories", kept)
                    root.put(key, acc)
                    anyChanged = true
                }
            }
            if (anyChanged) {
                writeRootUnlocked(root)
            }
        }
    }

    fun observeMessages(username: String, contactId: String): Flow<List<ChatMessage>> = changeTick.map {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(username) ?: return@withLock emptyList()
                val msgsObj = account.optJSONObject("messages") ?: return@withLock emptyList()
                val arr = msgsObj.optJSONArray(contactId) ?: return@withLock emptyList()
                val nowMs = System.currentTimeMillis()
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        val msg = jsonToChatMessage(obj)
                        if (msg.expiresAtMs <= 0L || msg.expiresAtMs > nowMs) {
                            list.add(msg)
                        }
                    }
                }
                list
            }
        }
    }

    suspend fun updateProfile(
        usernameKey: String,
        displayName: String? = null,
        newUsername: String? = null,
        bio: String? = null,
        dob: String? = null,
        photoData: String? = null,
        bannerData: String? = null
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val currentKey = findAccountKeyByUsernameUnlocked(root, usernameKey)
                    ?: throw IllegalStateException("User not found.")
                val account = root.optJSONObject(currentKey)
                    ?: throw IllegalStateException("User not found.")

                displayName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    require(it.length <= 60) { "Display name must be 60 characters or fewer." }
                    account.put("displayName", it)
                }

                var movedUsername: String? = null
                val previousStoredUsername = NomiChatRepository.normalizeUsername(account.optString("username", currentKey))
                newUsername?.let { rawHandle ->
                    val nextUsername = NomiChatRepository.normalizeUsername(rawHandle).take(40)
                    require(nextUsername.isNotEmpty()) { "Invalid username." }
                    require(nextUsername.length in 2..40) { "Username must be between 2 and 40 characters." }
                    val retired = readRetiredUsernamesUnlocked()
                    val isChanging = nextUsername != currentKey && nextUsername != previousStoredUsername
                    if (isChanging && (retired.contains(nextUsername) || findAccountKeyByUsernameUnlocked(root, nextUsername) != null)) {
                        throw IllegalStateException("This username is already taken / already added.")
                    }
                    if (!isChanging && retired.contains(nextUsername)) {
                        throw IllegalStateException("This username is already taken / already added.")
                    }
                    account.put("username", nextUsername)
                    account.put("userId", nextUsername)
                    if (nextUsername != currentKey) {
                        val contacts = account.optJSONArray("contacts")
                        if (contacts != null) {
                            for (i in 0 until contacts.length()) {
                                contacts.optJSONObject(i)?.put("userId", nextUsername)
                            }
                        }
                        val messagesObj = account.optJSONObject("messages")
                        if (messagesObj != null) {
                            val msgKeys = messagesObj.keys()
                            while (msgKeys.hasNext()) {
                                val cId = msgKeys.next()
                                val arr = messagesObj.optJSONArray(cId) ?: continue
                                for (i in 0 until arr.length()) {
                                    arr.optJSONObject(i)?.put("userId", nextUsername)
                                }
                            }
                        }
                        movedUsername = nextUsername
                    }
                }

                bio?.trim()?.let {
                    require(it.length <= 200) { "Bio must be 200 characters or fewer." }
                    account.put("bio", it)
                }
                dob?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    require(it.length in 4..20) { "Date of birth must be formatted cleanly (e.g., 2000-01-01)." }
                    account.put("dob", it)
                }
                photoData?.let {
                    require(it.length <= 150000) { "Profile photo is too large." }
                    account.put("photoData", it)
                }
                bannerData?.let {
                    require(it.length <= 150000) { "Profile banner is too large." }
                    account.put("bannerData", it)
                }
                account.put("updatedAtSec", System.currentTimeMillis() / 1000L)

                val finalDisplayName = account.optString("displayName", "")
                val finalUsername = account.optString("username", currentKey)
                val finalBio = account.optString("bio", "")
                val finalPhotoData = if (account.isNull("photoData")) null else account.optString("photoData").takeIf { it.isNotBlank() }
                val finalBannerData = if (account.isNull("bannerData")) null else account.optString("bannerData").takeIf { it.isNotBlank() }

                if (movedUsername != null) {
                    retireUsernameUnlocked(currentKey)
                    if (previousStoredUsername.isNotBlank() && previousStoredUsername != movedUsername) {
                        retireUsernameUnlocked(previousStoredUsername)
                    }
                    root.remove(currentKey)
                    root.put(movedUsername!!, account)
                } else {
                    root.put(currentKey, account)
                }

                // Propagate updated displayName/username/bio/photoData/bannerData to permitted peer contacts on other local accounts
                val allAccountKeys = root.keys()
                while (allAccountKeys.hasNext()) {
                    val otherKey = allAccountKeys.next()
                    if (otherKey == finalUsername) continue
                    val otherAccount = root.optJSONObject(otherKey) ?: continue
                    val otherUsername = otherAccount.optString("username", otherKey)
                    val visibleToOther = canAccountShowProfileTo(account, otherKey, otherUsername)
                    val otherContacts = otherAccount.optJSONArray("contacts") ?: continue
                    var changed = false
                    for (i in 0 until otherContacts.length()) {
                        val c = otherContacts.optJSONObject(i) ?: continue
                        if (c.optString("threadType", "chat") == "chat" &&
                            (c.optString("peerUid") == currentKey || c.optString("username") == currentKey)
                        ) {
                            if (finalDisplayName.isNotBlank() && !c.optBoolean("customRenamed", false)) {
                                c.put("name", finalDisplayName)
                            }
                            c.put("username", finalUsername)
                            if (c.optString("phone").startsWith("@") || c.optString("phone").isBlank()) {
                                c.put("phone", "@$finalUsername")
                            }
                            if (visibleToOther) {
                                c.put("bio", finalBio)
                                if (finalPhotoData != null) c.put("photoData", finalPhotoData) else c.put("photoData", JSONObject.NULL)
                                if (finalBannerData != null) c.put("bannerData", finalBannerData) else c.put("bannerData", JSONObject.NULL)
                            } else {
                                c.put("bio", "")
                                c.put("photoData", JSONObject.NULL)
                                c.put("bannerData", JSONObject.NULL)
                            }
                            c.put("peerUid", finalUsername)
                            c.put("isRegisteredPeer", true)
                            changed = true
                        }
                    }
                    if (changed) {
                        root.put(otherKey, otherAccount)
                    }
                }

                writeRootUnlocked(root)
                movedUsername
            }
        }
    }

    suspend fun updatePrivacySettings(
        usernameKey: String,
        profileVisibility: String,
        allowedViewerUids: List<String>,
        maxProfileViewers: Long,
        showOnlineStatus: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanVisibility = ProfileVisibilityMode.fromKey(profileVisibility).key
            val cleanAllowed = allowedViewerUids
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 128 }
                .distinct()
                .take(500)
            val cleanMax = maxProfileViewers.coerceIn(0L, 500L)
            mutex.withLock {
                val root = readRootUnlocked()
                val accountKey = findAccountKeyByUsernameUnlocked(root, usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val account = root.optJSONObject(accountKey)
                    ?: throw IllegalStateException("Account not found.")

                account.put("profileVisibility", cleanVisibility)
                account.put("allowedViewerUids", JSONArray(cleanAllowed))
                account.put("maxProfileViewers", cleanMax)
                account.put("showOnlineStatus", showOnlineStatus)
                account.put("online", showOnlineStatus)
                account.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                root.put(accountKey, account)

                val ownerHandle = account.optString("username", accountKey)
                val ownerBio = account.optString("bio", "")
                val ownerPhoto = if (account.isNull("photoData")) null else account.optString("photoData").takeIf { it.isNotBlank() }
                val ownerBanner = if (account.isNull("bannerData")) null else account.optString("bannerData").takeIf { it.isNotBlank() }

                val allKeys = root.keys()
                while (allKeys.hasNext()) {
                    val otherKey = allKeys.next()
                    if (otherKey == accountKey) continue
                    val otherAccount = root.optJSONObject(otherKey) ?: continue
                    val otherUsername = otherAccount.optString("username", otherKey)
                    val visibleToOther = canAccountShowProfileTo(account, otherKey, otherUsername)
                    val otherContacts = otherAccount.optJSONArray("contacts") ?: continue
                    var changed = false
                    for (i in 0 until otherContacts.length()) {
                        val c = otherContacts.optJSONObject(i) ?: continue
                        if (c.optString("threadType", "chat") == "chat" &&
                            (c.optString("peerUid") == accountKey || c.optString("username") == ownerHandle)
                        ) {
                            if (visibleToOther) {
                                c.put("bio", ownerBio)
                                if (ownerPhoto != null) c.put("photoData", ownerPhoto) else c.put("photoData", JSONObject.NULL)
                                if (ownerBanner != null) c.put("bannerData", ownerBanner) else c.put("bannerData", JSONObject.NULL)
                            } else {
                                c.put("bio", "")
                                c.put("photoData", JSONObject.NULL)
                                c.put("bannerData", JSONObject.NULL)
                            }
                            c.put("online", showOnlineStatus)
                            changed = true
                        }
                    }
                    if (changed) {
                        root.put(otherKey, otherAccount)
                    }
                }

                writeRootUnlocked(root)
            }
        }
    }

    suspend fun changePassword(
        usernameKey: String,
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(newPassword.length in 6..128) { "New password must be between 6 and 128 characters." }
            mutex.withLock {
                val root = readRootUnlocked()
                val accountKey = findAccountKeyByUsernameUnlocked(root, usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val account = root.optJSONObject(accountKey)
                    ?: throw IllegalStateException("Account not found.")
                val existingSalt = account.optString("passwordSalt", "")
                val existingHash = account.optString("passwordHash", "")
                if (existingSalt.isNotBlank() && existingHash.isNotBlank()) {
                    require(currentPassword.isNotEmpty()) { "Enter your current password." }
                    if (!PasswordHasher.verifyPassword(currentPassword, existingSalt, existingHash)) {
                        throw IllegalArgumentException("Current password is incorrect.")
                    }
                }
                val newSalt = PasswordHasher.generateSalt()
                val newHash = PasswordHasher.hashPassword(newPassword, newSalt)
                account.put("passwordSalt", newSalt)
                account.put("passwordHash", newHash)
                account.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                root.put(accountKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun refreshUserData(usernameKey: String): Result<Unit> = runCatching {
        purgeExpiredStories(usernameKey)
        changeTick.value = changeTick.value + 1L
    }

    suspend fun lookupUserByUsername(
        usernameInput: String,
        viewerUserId: String = ""
    ): Result<DirectoryLookupResult?> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHandle = NomiChatRepository.exactUsernameOrNull(usernameInput) ?: return@runCatching null
            mutex.withLock {
                val retired = readRetiredUsernamesUnlocked()
                if (retired.contains(cleanHandle)) return@runCatching null
                val root = readRootUnlocked()
                val key = findAccountKeyByUsernameUnlocked(root, cleanHandle) ?: return@runCatching null
                val obj = root.optJSONObject(key) ?: return@runCatching null
                if (obj.optString("accountStatus", "active") == "banned") {
                    throw IllegalStateException("This account is banned, so it cannot be added here.")
                }
                val storedUsername = obj.optString("username", key)
                if (!storedUsername.equals(cleanHandle, ignoreCase = true)) return@runCatching null
                val viewerAccount = if (viewerUserId.isNotBlank()) {
                    findAccountKeyByUsernameUnlocked(root, viewerUserId)?.let { root.optJSONObject(it) }
                } else null
                val viewerUsername = viewerAccount?.optString("username", viewerUserId).orEmpty()
                val visible = canAccountShowProfileTo(obj, viewerUserId, viewerUsername)
                val rawBio = if (visible) obj.optString("bio", "") else ""
                val rawPhoto = if (visible && !obj.isNull("photoData")) {
                    obj.optString("photoData").takeIf { it.isNotBlank() }
                } else null
                val rawBanner = if (visible && !obj.isNull("bannerData")) {
                    obj.optString("bannerData").takeIf { it.isNotBlank() }
                } else null
                DirectoryLookupResult(
                    username = storedUsername,
                    displayName = obj.optString("displayName", storedUsername),
                    ownerUid = key,
                    bio = rawBio,
                    photoData = rawPhoto,
                    bannerData = rawBanner
                )
            }
        }
    }

    suspend fun addContact(
        usernameKey: String,
        name: String,
        contactUsername: String,
        phone: String,
        threadType: String,
        peerUid: String = "",
        isRegisteredPeer: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val validType = if (threadType == "group") "group" else "chat"
                val rawHandle = contactUsername.trim().removePrefix("@")
                val nowSec = System.currentTimeMillis() / 1000L
                val contactId = "c_${UUID.randomUUID().toString().take(8)}"

                val cleanHandle: String
                val resolvedPeerUid: String
                val resolvedIsPeer: Boolean
                val peerOnline: Boolean
                val peerBio: String
                val peerPhotoData: String?
                val peerBannerData: String?
                val finalName: String

                if (validType == "chat") {
                    cleanHandle = NomiChatRepository.exactUsernameOrNull(contactUsername)
                        ?: throw IllegalStateException("Account Not Found")
                    val retired = readRetiredUsernamesUnlocked()
                    if (retired.contains(cleanHandle)) {
                        throw IllegalStateException("Account Not Found")
                    }
                    val foundKey = findAccountKeyByUsernameUnlocked(root, cleanHandle)
                        ?: throw IllegalStateException("Account Not Found")
                    if (foundKey == usernameKey) {
                        throw IllegalStateException("You cannot add your own username.")
                    }
                    val candidateObj = root.optJSONObject(foundKey)
                    if (candidateObj == null ||
                        !candidateObj.optString("username", foundKey).equals(cleanHandle, ignoreCase = true)
                    ) {
                        throw IllegalStateException("Account Not Found")
                    }
                    if (candidateObj.optString("accountStatus", "active") == "banned") {
                        throw IllegalStateException("This account is banned, so it cannot be added here.")
                    }
                    resolvedPeerUid = foundKey
                    resolvedIsPeer = true
                    val registeredPeerDisplayName = candidateObj.optString("displayName").trim()
                    peerOnline = candidateObj.optBoolean("showOnlineStatus", true) &&
                        candidateObj.optBoolean("online", false)
                    val viewerHandle = account.optString("username", usernameKey)
                    val visible = canAccountShowProfileTo(candidateObj, usernameKey, viewerHandle)
                    peerBio = if (visible) candidateObj.optString("bio", "").take(200) else ""
                    peerPhotoData = if (visible && !candidateObj.isNull("photoData")) {
                        candidateObj.optString("photoData").takeIf { it.isNotBlank() }
                    } else null
                    peerBannerData = if (visible && !candidateObj.isNull("bannerData")) {
                        candidateObj.optString("bannerData").takeIf { it.isNotBlank() }
                    } else null
                    finalName = registeredPeerDisplayName.ifBlank { cleanHandle }.take(60)
                } else {
                    cleanHandle = NomiChatRepository.normalizeUsername(rawHandle.ifBlank { name })
                        .ifBlank { "nomigroup" }.take(40)
                    resolvedPeerUid = peerUid
                    resolvedIsPeer = isRegisteredPeer
                    peerOnline = false
                    peerBio = ""
                    peerPhotoData = null
                    peerBannerData = null
                    finalName = name.trim().ifBlank { cleanHandle }.take(60)
                    require(finalName.isNotEmpty()) { "Group name cannot be empty." }
                }
                val cleanPhone = phone.trim().ifBlank { "@$cleanHandle" }.take(30)

                val existingContacts = account.optJSONArray("contacts") ?: JSONArray()
                for (i in 0 until existingContacts.length()) {
                    val existingObj = existingContacts.optJSONObject(i) ?: continue
                    if (existingObj.optString("username") == cleanHandle && validType == "chat") {
                        if (!existingObj.optBoolean("customRenamed", false)) {
                            existingObj.put("name", finalName)
                        }
                        existingObj.put("bio", peerBio)
                        if (peerPhotoData != null) {
                            existingObj.put("photoData", peerPhotoData)
                        } else {
                            existingObj.put("photoData", JSONObject.NULL)
                        }
                        if (peerBannerData != null) {
                            existingObj.put("bannerData", peerBannerData)
                        } else {
                            existingObj.put("bannerData", JSONObject.NULL)
                        }
                        existingObj.put("peerUid", resolvedPeerUid)
                        existingObj.put("isRegisteredPeer", resolvedIsPeer)
                        existingObj.put("online", peerOnline)
                        existingObj.put("updatedAtSec", nowSec)
                        root.put(usernameKey, account)
                        writeRootUnlocked(root)
                        return@withLock existingObj.optString("id")
                    }
                }

                val updatedContacts = JSONArray()
                updatedContacts.put(
                    JSONObject().apply {
                        put("id", contactId)
                        put("userId", usernameKey)
                        put("name", finalName)
                        put("username", cleanHandle)
                        put("phone", cleanPhone)
                        put("threadType", validType)
                        put("bio", peerBio)
                        if (peerPhotoData != null) put("photoData", peerPhotoData) else put("photoData", JSONObject.NULL)
                        if (peerBannerData != null) put("bannerData", peerBannerData) else put("bannerData", JSONObject.NULL)
                        put("online", peerOnline)
                        put("typing", false)
                        put("unreadCount", 0L)
                        put("lastMessageText", "")
                        put("lastMessageFromMe", false)
                        put("lastMessageTimeLabel", "")
                        put("pinned", false)
                        put("locked", false)
                        put("blocked", false)
                        put("archived", false)
                        put("peerUid", resolvedPeerUid)
                        put("isRegisteredPeer", resolvedIsPeer)
                        put("accountStatus", "active")
                        put("reportCount", 0L)
                        put("reportedBy", JSONArray())
                        put("createdAtSec", nowSec)
                        put("updatedAtSec", nowSec)
                    }
                )
                for (i in 0 until existingContacts.length()) {
                    existingContacts.optJSONObject(i)?.let { updatedContacts.put(it) }
                }
                account.put("contacts", updatedContacts)

                val messagesObj = account.optJSONObject("messages") ?: JSONObject()
                messagesObj.put(contactId, JSONArray())
                account.put("messages", messagesObj)

                root.put(usernameKey, account)
                writeRootUnlocked(root)
                contactId
            }
        }
    }

    suspend fun setContactTyping(
        usernameKey: String,
        contactId: String,
        isTyping: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("typing", isTyping)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun sendMessage(
        usernameKey: String,
        contactId: String,
        sender: String,
        text: String,
        aiGenerated: Boolean,
        incrementUnreadIfThem: Boolean = true,
        messageType: String = "text",
        mediaData: String? = null,
        durationSec: Long = 0L,
        replyToText: String? = null,
        replyToSender: String? = null,
        replyToMessageId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val cleanType = when (messageType) {
                    "voice", "image", "file", "call" -> messageType
                    else -> "text"
                }
                val cleanMedia = mediaData?.takeIf { it.isNotBlank() && it.length <= 240_000 }
                val cleanDuration = durationSec.coerceIn(0L, 600L)
                val fallbackLabel = when (cleanType) {
                    "voice" -> "🎤 Voice message (${formatVoiceDuration(cleanDuration)})"
                    "image" -> "📷 Photo"
                    "file" -> "📎 Attachment"
                    "call" -> "📞 Voice call"
                    else -> ""
                }
                val cleanText = text.trim().ifBlank { fallbackLabel }.take(2000)
                require(cleanText.isNotEmpty()) { "Message cannot be empty." }
                val validSender = if (sender == "them") "them" else "me"
                val timeLabel = currentFormattedTime()
                val nowSec = System.currentTimeMillis() / 1000L

                var targetPeerHandle = ""
                var targetThreadType = "chat"
                var disappearingSec = 0L
                val contacts = account.optJSONArray("contacts") ?: JSONArray()
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        val currentUnread = obj.optLong("unreadCount", 0L)
                        val nextUnread = if (validSender == "them" && incrementUnreadIfThem) {
                            currentUnread + 1L
                        } else {
                            0L
                        }
                        disappearingSec = obj.optLong("disappearingSeconds", 0L).coerceAtLeast(0L)
                        obj.put("lastMessageText", cleanText.take(1000))
                        obj.put("lastMessageFromMe", validSender == "me")
                        obj.put("lastMessageTimeLabel", timeLabel)
                        obj.put("unreadCount", nextUnread)
                        obj.put("typing", false)
                        obj.put("updatedAtSec", nowSec)
                        targetPeerHandle = obj.optString("username", "")
                        targetThreadType = obj.optString("threadType", "chat")
                        break
                    }
                }
                val expiresAtMs = if (disappearingSec > 0L) {
                    System.currentTimeMillis() + disappearingSec * 1000L
                } else {
                    0L
                }

                val messagesObj = account.optJSONObject("messages") ?: JSONObject()
                val msgArr = messagesObj.optJSONArray(contactId) ?: JSONArray()
                msgArr.put(
                    JSONObject().apply {
                        put("id", "m_${UUID.randomUUID().toString().take(8)}")
                        put("userId", usernameKey)
                        put("contactId", contactId)
                        put("sender", validSender)
                        put("text", cleanText)
                        put("messageType", cleanType)
                        if (cleanMedia != null) put("mediaData", cleanMedia) else put("mediaData", JSONObject.NULL)
                        put("durationSec", cleanDuration)
                        put("expiresAtMs", expiresAtMs)
                        put("timeLabel", timeLabel)
                        put("aiGenerated", aiGenerated)
                        put("createdAtSec", nowSec)
                        if (!replyToText.isNullOrBlank()) put("replyToText", replyToText) else put("replyToText", JSONObject.NULL)
                        if (!replyToSender.isNullOrBlank()) put("replyToSender", replyToSender) else put("replyToSender", JSONObject.NULL)
                        if (!replyToMessageId.isNullOrBlank()) put("replyToMessageId", replyToMessageId) else put("replyToMessageId", JSONObject.NULL)
                    }
                )
                messagesObj.put(contactId, msgArr)
                account.put("messages", messagesObj)
                account.put("contacts", contacts)
                root.put(usernameKey, account)

                // Cross-account local delivery if target handle is another registered local account
                if (validSender == "me" && targetThreadType == "chat" && targetPeerHandle.isNotBlank()) {
                    val peerKey = findAccountKeyByUsernameUnlocked(root, targetPeerHandle)
                    if (peerKey != null && peerKey != usernameKey) {
                        val peerAccount = root.optJSONObject(peerKey)
                        if (peerAccount != null && peerAccount.optString("accountStatus", "active") != "banned") {
                            val peerUsernameHandle = peerAccount.optString("username", peerKey)
                            val visibleToPeer = canAccountShowProfileTo(account, peerKey, peerUsernameHandle)
                            val senderBio = if (visibleToPeer) account.optString("bio", "").take(200) else ""
                            val senderPhoto = if (visibleToPeer && !account.isNull("photoData")) {
                                account.optString("photoData").takeIf { it.isNotBlank() }
                            } else null
                            val senderBanner = if (visibleToPeer && !account.isNull("bannerData")) {
                                account.optString("bannerData").takeIf { it.isNotBlank() }
                            } else null
                            deliverToLocalPeerUnlocked(
                                peerKey = peerKey,
                                peerAccount = peerAccount,
                                senderHandle = account.optString("username", usernameKey),
                                senderDisplayName = account.optString("displayName", usernameKey),
                                senderBio = senderBio,
                                senderPhotoData = senderPhoto,
                                senderBannerData = senderBanner,
                                visibleToPeer = visibleToPeer,
                                senderKey = usernameKey,
                                text = cleanText,
                                messageType = cleanType,
                                mediaData = cleanMedia,
                                durationSec = cleanDuration,
                                timeLabel = timeLabel,
                                nowSec = nowSec,
                                replyToText = replyToText,
                                replyToSender = replyToSender,
                                replyToMessageId = replyToMessageId
                            )
                            root.put(peerKey, peerAccount)
                        }
                    }
                }

                writeRootUnlocked(root)
            }
        }
    }

    private fun deliverToLocalPeerUnlocked(
        peerKey: String,
        peerAccount: JSONObject,
        senderHandle: String,
        senderDisplayName: String,
        senderBio: String,
        senderPhotoData: String?,
        senderBannerData: String?,
        visibleToPeer: Boolean,
        senderKey: String,
        text: String,
        messageType: String,
        mediaData: String?,
        durationSec: Long,
        timeLabel: String,
        nowSec: Long,
        replyToText: String? = null,
        replyToSender: String? = null,
        replyToMessageId: String? = null
    ) {
        val peerContacts = peerAccount.optJSONArray("contacts") ?: JSONArray()
        var peerContactObj: JSONObject? = null
        var peerContactId = ""

        for (i in 0 until peerContacts.length()) {
            val obj = peerContacts.optJSONObject(i) ?: continue
            if (obj.optString("username") == senderHandle || obj.optString("peerUid") == senderKey) {
                peerContactObj = obj
                peerContactId = obj.optString("id")
                break
            }
        }

        if (peerContactObj?.optBoolean("blocked", false) == true) {
            return
        }
        val isMuted = peerContactObj?.optBoolean("muted", false) == true
        val peerDisappearingSec = (peerContactObj?.optLong("disappearingSeconds", 0L) ?: 0L).coerceAtLeast(0L)
        val expiresAtMs = if (peerDisappearingSec > 0L) {
            System.currentTimeMillis() + peerDisappearingSec * 1000L
        } else {
            0L
        }

        if (peerContactObj == null) {
            peerContactId = "c_${UUID.randomUUID().toString().take(8)}"
            val newContact = JSONObject().apply {
                put("id", peerContactId)
                put("userId", peerKey)
                put("name", senderDisplayName)
                put("username", senderHandle)
                put("phone", "@$senderHandle")
                put("threadType", "chat")
                put("bio", senderBio)
                if (senderPhotoData != null) put("photoData", senderPhotoData) else put("photoData", JSONObject.NULL)
                if (senderBannerData != null) put("bannerData", senderBannerData) else put("bannerData", JSONObject.NULL)
                put("online", false)
                put("typing", false)
                put("unreadCount", 1L)
                put("lastMessageText", text.take(1000))
                put("lastMessageFromMe", false)
                put("lastMessageTimeLabel", timeLabel)
                put("pinned", false)
                put("locked", false)
                put("blocked", false)
                put("archived", false)
                put("muted", false)
                put("disappearingSeconds", 0L)
                put("chatTheme", "classic")
                put("peerUid", senderKey)
                put("isRegisteredPeer", true)
                put("accountStatus", "active")
                put("reportCount", 0L)
                put("reportedBy", JSONArray())
                put("createdAtSec", nowSec)
                put("updatedAtSec", nowSec)
            }
            val updatedContacts = JSONArray()
            updatedContacts.put(newContact)
            for (i in 0 until peerContacts.length()) {
                peerContacts.optJSONObject(i)?.let { updatedContacts.put(it) }
            }
            peerAccount.put("contacts", updatedContacts)
        } else {
            val prevUnread = peerContactObj.optLong("unreadCount", 0L)
            if (!peerContactObj.optBoolean("customRenamed", false)) {
                peerContactObj.put("name", senderDisplayName)
            }
            peerContactObj.put("bio", senderBio)
            if (!visibleToPeer) {
                peerContactObj.put("photoData", JSONObject.NULL)
                peerContactObj.put("bannerData", JSONObject.NULL)
            } else {
                if (senderPhotoData != null) {
                    peerContactObj.put("photoData", senderPhotoData)
                }
                if (senderBannerData != null) {
                    peerContactObj.put("bannerData", senderBannerData)
                }
            }
            peerContactObj.put("lastMessageText", text.take(1000))
            peerContactObj.put("lastMessageFromMe", false)
            peerContactObj.put("lastMessageTimeLabel", timeLabel)
            peerContactObj.put("unreadCount", prevUnread + 1L)
            peerContactObj.put("typing", false)
            peerContactObj.put("updatedAtSec", nowSec)
            peerAccount.put("contacts", peerContacts)
        }

        val peerDeleted = peerAccount.optJSONArray("deletedIds")
        if (peerDeleted != null) {
            val keptDeleted = JSONArray()
            for (i in 0 until peerDeleted.length()) {
                val id = peerDeleted.optString(i)
                if (id != peerContactId) keptDeleted.put(id)
            }
            peerAccount.put("deletedIds", keptDeleted)
        }

        val peerMessagesObj = peerAccount.optJSONObject("messages") ?: JSONObject()
        val peerMsgArr = peerMessagesObj.optJSONArray(peerContactId) ?: JSONArray()
        peerMsgArr.put(
            JSONObject().apply {
                put("id", "m_${UUID.randomUUID().toString().take(8)}")
                put("userId", peerKey)
                put("contactId", peerContactId)
                put("sender", "them")
                put("text", text)
                put("messageType", messageType)
                if (mediaData != null) put("mediaData", mediaData) else put("mediaData", JSONObject.NULL)
                put("durationSec", durationSec)
                put("expiresAtMs", expiresAtMs)
                put("timeLabel", timeLabel)
                put("aiGenerated", false)
                put("createdAtSec", nowSec)
                if (!replyToText.isNullOrBlank()) put("replyToText", replyToText) else put("replyToText", JSONObject.NULL)
                if (!replyToSender.isNullOrBlank()) put("replyToSender", replyToSender) else put("replyToSender", JSONObject.NULL)
                if (!replyToMessageId.isNullOrBlank()) put("replyToMessageId", replyToMessageId) else put("replyToMessageId", JSONObject.NULL)
            }
        )
        peerMessagesObj.put(peerContactId, peerMsgArr)
        peerAccount.put("messages", peerMessagesObj)
        if (!isMuted) {
            _incomingAlerts.tryEmit(
                IncomingMessageAlert(
                    contactId = peerContactId,
                    senderName = senderDisplayName,
                    senderHandle = senderHandle,
                    text = text,
                    recipientUserId = peerKey
                )
            )
        }
    }

    suspend fun markThreadRead(usernameKey: String, contactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("unreadCount", 0L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun toggleLockChat(
        usernameKey: String,
        contactId: String,
        locked: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("locked", locked)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun toggleBlockChat(
        usernameKey: String,
        contactId: String,
        blocked: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("blocked", blocked)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun toggleArchiveChat(
        usernameKey: String,
        contactId: String,
        archived: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("archived", archived)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun toggleMuteChat(
        usernameKey: String,
        contactId: String,
        muted: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("muted", muted)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun setDisappearingMessages(
        usernameKey: String,
        contactId: String,
        seconds: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("disappearingSeconds", seconds.coerceIn(0L, 7_776_000L))
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun setChatTheme(
        usernameKey: String,
        contactId: String,
        themeKey: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val validKey = com.example.data.model.ChatThemeStyle.fromKey(themeKey).key
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("chatTheme", validKey)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun clearChatMessages(
        usernameKey: String,
        contactId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("lastMessageText", "")
                        obj.put("lastMessageFromMe", false)
                        obj.put("lastMessageTimeLabel", "")
                        obj.put("unreadCount", 0L)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                val messagesObj = account.optJSONObject("messages") ?: JSONObject()
                messagesObj.put(contactId, JSONArray())
                account.put("messages", messagesObj)
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun renameChat(
        usernameKey: String,
        contactId: String,
        newName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = newName.trim().take(60)
            require(cleanName.isNotEmpty()) { "Chat name cannot be empty." }
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: return@withLock
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contactId) {
                        obj.put("name", cleanName)
                        obj.put("customRenamed", true)
                        obj.put("updatedAtSec", System.currentTimeMillis() / 1000L)
                        break
                    }
                }
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun deleteChatPermanently(
        usernameKey: String,
        contactId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@withLock
                val contacts = account.optJSONArray("contacts") ?: JSONArray()
                val filteredContacts = JSONArray()
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") != contactId) {
                        filteredContacts.put(obj)
                    }
                }
                account.put("contacts", filteredContacts)

                account.optJSONObject("messages")?.remove(contactId)

                val deletedArr = account.optJSONArray("deletedIds") ?: JSONArray()
                var alreadyRecorded = false
                for (i in 0 until deletedArr.length()) {
                    if (deletedArr.optString(i) == contactId) {
                        alreadyRecorded = true
                        break
                    }
                }
                if (!alreadyRecorded) {
                    deletedArr.put(contactId)
                }
                account.put("deletedIds", deletedArr)

                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    suspend fun reportUserAccount(
        usernameKey: String,
        contact: ChatThread,
        reporterUsername: String
    ): Result<ReportOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val contacts = account.optJSONArray("contacts") ?: JSONArray()
                val reporterKey = NomiChatRepository.normalizeUsername(reporterUsername.ifBlank { usernameKey })

                var targetObj: JSONObject? = null
                for (i in 0 until contacts.length()) {
                    val obj = contacts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == contact.id) {
                        targetObj = obj
                        break
                    }
                }
                if (targetObj == null) {
                    return@withLock ReportOutcome(
                        ok = false,
                        message = "Contact not found.",
                        status = contact.accountStatus,
                        count = contact.reportCount
                    )
                }

                val reportedArr = targetObj.optJSONArray("reportedBy") ?: JSONArray()
                val reporters = mutableListOf<String>()
                for (i in 0 until reportedArr.length()) {
                    reporters.add(reportedArr.optString(i))
                }

                if (reporters.contains(reporterKey)) {
                    return@withLock ReportOutcome(
                        ok = false,
                        message = "You have already reported this account.",
                        status = targetObj.optString("accountStatus", "active"),
                        count = targetObj.optLong("reportCount", reporters.size.toLong())
                    )
                }

                reporters.add(reporterKey)
                val updatedCount = reporters.size.toLong()
                val updatedStatus = if (updatedCount >= BAN_REPORT_LIMIT) "banned" else "active"

                targetObj.put("reportedBy", JSONArray(reporters))
                targetObj.put("reportCount", updatedCount)
                targetObj.put("accountStatus", updatedStatus)
                targetObj.put("updatedAtSec", System.currentTimeMillis() / 1000L)

                root.put(usernameKey, account)
                writeRootUnlocked(root)

                val feedback = if (updatedStatus == "banned") {
                    "Thanks for reporting ${contact.name}. This account has been suspended."
                } else {
                    "Thank you for reporting ${contact.name}. Our moderation team will review this account."
                }
                ReportOutcome(ok = true, message = feedback, status = updatedStatus, count = updatedCount)
            }
        }
    }

    private fun canAccountShowProfileTo(
        ownerAccount: JSONObject,
        viewerKey: String,
        viewerUsername: String = ""
    ): Boolean {
        val ownerUid = ownerAccount.optString("userId", ownerAccount.optString("username", ""))
        val visibility = ownerAccount.optString("profileVisibility", ProfileVisibilityMode.EVERYONE.key)
        val allowedArr = ownerAccount.optJSONArray("allowedViewerUids") ?: JSONArray()
        val allowedList = List(allowedArr.length()) { idx -> allowedArr.optString(idx) }
            .filter { it.isNotBlank() }
        val maxViewers = ownerAccount.optLong("maxProfileViewers", 0L)
        return NomiChatRepository.canViewerSeeProfile(
            profileVisibility = visibility,
            allowedViewerUids = allowedList,
            maxProfileViewers = maxViewers,
            ownerUid = ownerUid,
            viewerUid = viewerKey,
            viewerUsername = viewerUsername
        )
    }

    private fun jsonToUserProfile(obj: JSONObject): UserProfile {
        val createdSec = obj.optLong("createdAtSec", 0L)
        val updatedSec = obj.optLong("updatedAtSec", 0L)
        val rawPhoto = if (obj.isNull("photoData")) null else obj.optString("photoData").takeIf { it.isNotBlank() }
        val rawBanner = if (obj.isNull("bannerData")) null else obj.optString("bannerData").takeIf { it.isNotBlank() }
        val allowedArr = obj.optJSONArray("allowedViewerUids") ?: JSONArray()
        val allowedList = List(allowedArr.length()) { idx -> allowedArr.optString(idx) }
            .filter { it.isNotBlank() }
        return UserProfile(
            id = obj.optString("userId", ""),
            userId = obj.optString("userId", ""),
            displayName = obj.optString("displayName", ""),
            username = obj.optString("username", ""),
            bio = obj.optString("bio", "Dream Big... 💚"),
            dob = obj.optString("dob", "2000-01-01"),
            provider = obj.optString("provider", "username"),
            photoData = rawPhoto,
            bannerData = rawBanner,
            profileVisibility = ProfileVisibilityMode.fromKey(
                obj.optString("profileVisibility", ProfileVisibilityMode.EVERYONE.key)
            ).key,
            allowedViewerUids = allowedList,
            maxProfileViewers = obj.optLong("maxProfileViewers", 0L).coerceIn(0L, 500L),
            online = obj.optBoolean("online", false),
            showOnlineStatus = obj.optBoolean("showOnlineStatus", true),
            verified = true,
            premiumUnlocked = true,
            accountStatus = obj.optString("accountStatus", "active"),
            reportCount = obj.optLong("reportCount", 0L),
            createdAt = Timestamp(createdSec, 0),
            updatedAt = Timestamp(updatedSec, 0)
        )
    }

    private fun jsonToChatThread(obj: JSONObject): ChatThread {
        val reportedArr = obj.optJSONArray("reportedBy") ?: JSONArray()
        val reporters = List(reportedArr.length()) { idx -> reportedArr.optString(idx) }
        val rawPhoto = if (obj.isNull("photoData")) null else obj.optString("photoData").takeIf { it.isNotBlank() }
        val rawBanner = if (obj.isNull("bannerData")) null else obj.optString("bannerData").takeIf { it.isNotBlank() }
        return ChatThread(
            id = obj.optString("id", ""),
            userId = obj.optString("userId", ""),
            name = obj.optString("name", ""),
            username = obj.optString("username", ""),
            phone = obj.optString("phone", ""),
            threadType = obj.optString("threadType", "chat"),
            bio = obj.optString("bio", ""),
            photoData = rawPhoto,
            bannerData = rawBanner,
            online = obj.optBoolean("online", false),
            typing = obj.optBoolean("typing", false),
            unreadCount = obj.optLong("unreadCount", 0L),
            lastMessageText = obj.optString("lastMessageText", ""),
            lastMessageFromMe = obj.optBoolean("lastMessageFromMe", false),
            lastMessageTimeLabel = obj.optString("lastMessageTimeLabel", ""),
            pinned = obj.optBoolean("pinned", false),
            locked = obj.optBoolean("locked", false),
            blocked = obj.optBoolean("blocked", false),
            archived = obj.optBoolean("archived", false),
            muted = obj.optBoolean("muted", false),
            disappearingSeconds = obj.optLong("disappearingSeconds", 0L),
            chatTheme = obj.optString("chatTheme", com.example.data.model.ChatThemeStyle.CLASSIC.key),
            customRenamed = obj.optBoolean("customRenamed", false),
            peerUid = obj.optString("peerUid", ""),
            isRegisteredPeer = obj.optBoolean("isRegisteredPeer", false),
            accountStatus = obj.optString("accountStatus", "active"),
            reportCount = obj.optLong("reportCount", 0L),
            reportedBy = reporters,
            createdAt = Timestamp(obj.optLong("createdAtSec", 0L), 0),
            updatedAt = Timestamp(obj.optLong("updatedAtSec", 0L), 0)
        )
    }

    private fun jsonToStoryItem(
        obj: JSONObject,
        fallbackName: String,
        fallbackUsername: String,
        fallbackPhoto: String?
    ): StoryItem {
        val rawAuthorPhoto = if (obj.isNull("authorPhotoData")) null else obj.optString("authorPhotoData").takeIf { it.isNotBlank() }
        val createdMs = obj.optLong("createdAtMs", 0L)
        val viewedArr = obj.optJSONArray("viewedByUids")
        val viewedList = if (viewedArr != null) {
            buildList {
                for (i in 0 until viewedArr.length()) {
                    viewedArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } else emptyList()
        val likedArr = obj.optJSONArray("likedByUids")
        val likedList = if (likedArr != null) {
            buildList {
                for (i in 0 until likedArr.length()) {
                    likedArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } else emptyList()
        return StoryItem(
            id = obj.optString("id", ""),
            userId = obj.optString("userId", ""),
            authorName = obj.optString("authorName", fallbackName).ifBlank { fallbackName },
            authorUsername = obj.optString("authorUsername", fallbackUsername).ifBlank { fallbackUsername },
            authorPhotoData = fallbackPhoto ?: rawAuthorPhoto,
            mediaData = obj.optString("mediaData", ""),
            mediaType = obj.optString("mediaType", "image").ifBlank { "image" },
            caption = obj.optString("caption", ""),
            storyFont = StoryFontStyle.fromKey(obj.optString("storyFont", StoryFontStyle.NOTO_NASTALIQ.key)).key,
            musicTag = obj.optString("musicTag", ""),
            overlayText = obj.optString("overlayText", ""),
            overlayColorHex = obj.optLong("overlayColorHex", 0xFFFFFFFFL),
            overlayYFraction = obj.optDouble("overlayYFraction", 0.5),
            stickersJson = obj.optString("stickersJson", ""),
            drawingJson = obj.optString("drawingJson", ""),
            cropMode = obj.optString("cropMode", "crop"),
            rotationDegrees = obj.optLong("rotationDegrees", 0L),
            viewedByUids = viewedList,
            likedByUids = likedList,
            createdAtMs = createdMs,
            expiresAtMs = obj.optLong("expiresAtMs", createdMs + STORY_DURATION_MS),
            createdAt = Timestamp(createdMs / 1000L, 0)
        )
    }

    private fun jsonToChatMessage(obj: JSONObject): ChatMessage {
        val rawMedia = if (obj.isNull("mediaData")) null else obj.optString("mediaData").takeIf { it.isNotBlank() }
        val rawReplyText = if (obj.isNull("replyToText")) null else obj.optString("replyToText").takeIf { it.isNotBlank() }
        val rawReplySender = if (obj.isNull("replyToSender")) null else obj.optString("replyToSender").takeIf { it.isNotBlank() }
        val rawReplyMsgId = if (obj.isNull("replyToMessageId")) null else obj.optString("replyToMessageId").takeIf { it.isNotBlank() }
        return ChatMessage(
            id = obj.optString("id", ""),
            userId = obj.optString("userId", ""),
            contactId = obj.optString("contactId", ""),
            sender = obj.optString("sender", "me"),
            text = obj.optString("text", ""),
            messageType = obj.optString("messageType", "text"),
            mediaData = rawMedia,
            durationSec = obj.optLong("durationSec", 0L),
            expiresAtMs = obj.optLong("expiresAtMs", 0L),
            replyToText = rawReplyText,
            replyToSender = rawReplySender,
            replyToMessageId = rawReplyMsgId,
            timeLabel = obj.optString("timeLabel", ""),
            aiGenerated = obj.optBoolean("aiGenerated", false),
            createdAt = Timestamp(obj.optLong("createdAtSec", 0L), 0)
        )
    }

    suspend fun deleteMessage(
        usernameKey: String,
        contactId: String,
        messageId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey) ?: return@runCatching
                val messagesObj = account.optJSONObject("messages") ?: return@runCatching
                val msgArr = messagesObj.optJSONArray(contactId) ?: return@runCatching
                val newArr = JSONArray()
                for (i in 0 until msgArr.length()) {
                    val m = msgArr.optJSONObject(i) ?: continue
                    if (m.optString("id") != messageId) {
                        newArr.put(m)
                    }
                }
                messagesObj.put(contactId, newArr)
                account.put("messages", messagesObj)
                root.put(usernameKey, account)
                writeRootUnlocked(root)
            }
        }
    }

    private fun currentFormattedTime(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    private fun formatVoiceDuration(durationSec: Long): String {
        val sec = durationSec.coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L)
    }
}

class LocalNomiRepository(private val store: LocalAuthStore) : NomiDataSource {
    override val requiresInitialCloudSetup: Boolean = false
    override val incomingAlerts: Flow<IncomingMessageAlert> = store.incomingAlerts

    override suspend fun setUserPresence(userId: String, online: Boolean) {
        store.setUserPresence(userId, online)
    }

    override fun observeUserProfile(userId: String): Flow<UserProfile?> =
        store.observeProfile(userId)

    override fun observeContacts(userId: String): Flow<List<ChatThread>> =
        store.observeContacts(userId)

    override fun observeMessages(userId: String, contactId: String): Flow<List<ChatMessage>> =
        store.observeMessages(userId, contactId)

    override fun observeStories(userId: String): Flow<List<StoryItem>> =
        store.observeStories(userId)

    override fun observeStoryViews(userId: String, storyId: String): Flow<List<StoryViewRecord>> =
        store.observeStoryViews(userId, storyId)

    override suspend fun postStory(
        userId: String,
        mediaData: String,
        caption: String,
        storyFont: String,
        mediaType: String,
        musicTag: String,
        overlayText: String,
        overlayColorHex: Long,
        overlayYFraction: Double,
        stickersJson: String,
        drawingJson: String,
        cropMode: String,
        rotationDegrees: Long
    ): Result<String> = store.postStory(
        usernameKey = userId,
        mediaData = mediaData,
        caption = caption,
        storyFont = storyFont,
        mediaType = mediaType,
        musicTag = musicTag,
        overlayText = overlayText,
        overlayColorHex = overlayColorHex,
        overlayYFraction = overlayYFraction,
        stickersJson = stickersJson,
        drawingJson = drawingJson,
        cropMode = cropMode,
        rotationDegrees = rotationDegrees
    )

    override suspend fun recordStoryView(userId: String, storyId: String): Result<Unit> =
        store.recordStoryView(userId, storyId)

    override suspend fun reactToStory(
        userId: String,
        storyId: String,
        liked: Boolean?,
        reactionEmoji: String?
    ): Result<Unit> = store.reactToStory(userId, storyId, liked, reactionEmoji)

    override suspend fun replyToStory(
        userId: String,
        story: StoryItem,
        replyText: String
    ): Result<Unit> = store.replyToStory(userId, story, replyText)

    override suspend fun deleteStory(userId: String, storyId: String): Result<Unit> =
        store.deleteStory(userId, storyId)

    override suspend fun purgeExpiredStories(userId: String) =
        store.purgeExpiredStories(userId)

    override suspend fun updateProfile(
        userId: String,
        displayName: String?,
        username: String?,
        bio: String?,
        dob: String?,
        photoData: String?,
        bannerData: String?
    ): Result<String?> = store.updateProfile(userId, displayName, username, bio, dob, photoData, bannerData)

    override suspend fun updatePrivacySettings(
        userId: String,
        profileVisibility: String,
        allowedViewerUids: List<String>,
        maxProfileViewers: Long,
        showOnlineStatus: Boolean
    ): Result<Unit> = store.updatePrivacySettings(userId, profileVisibility, allowedViewerUids, maxProfileViewers, showOnlineStatus)

    override suspend fun changePassword(
        userId: String,
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = store.changePassword(userId, currentPassword, newPassword)

    override suspend fun refreshUserData(userId: String): Result<Unit> =
        store.refreshUserData(userId)

    override suspend fun syncAccountSettings(
        userId: String,
        themeMode: ThemeMode?,
        notificationsEnabled: Boolean?,
        smartAiEnabled: Boolean?,
        appLockPinHash: String?
    ) = store.updateAccountSettings(
        usernameKey = userId,
        themeMode = themeMode,
        notificationsEnabled = notificationsEnabled,
        smartAiEnabled = smartAiEnabled,
        appLockPinHash = appLockPinHash
    )

    override suspend fun lookupUserByUsername(
        username: String,
        viewerUserId: String
    ): Result<DirectoryLookupResult?> = store.lookupUserByUsername(username, viewerUserId)

    override suspend fun addContact(
        userId: String,
        name: String,
        username: String,
        phone: String,
        threadType: String,
        peerUid: String,
        isRegisteredPeer: Boolean
    ): Result<String> = store.addContact(userId, name, username, phone, threadType, peerUid, isRegisteredPeer)

    override suspend fun setContactTyping(
        userId: String,
        contactId: String,
        isTyping: Boolean
    ): Result<Unit> = store.setContactTyping(userId, contactId, isTyping)

    override suspend fun sendMessage(
        userId: String,
        contactId: String,
        sender: String,
        text: String,
        aiGenerated: Boolean,
        incrementUnreadIfThem: Boolean,
        messageType: String,
        mediaData: String?,
        durationSec: Long,
        replyToText: String?,
        replyToSender: String?,
        replyToMessageId: String?
    ): Result<Unit> = store.sendMessage(
        usernameKey = userId,
        contactId = contactId,
        sender = sender,
        text = text,
        aiGenerated = aiGenerated,
        incrementUnreadIfThem = incrementUnreadIfThem,
        messageType = messageType,
        mediaData = mediaData,
        durationSec = durationSec,
        replyToText = replyToText,
        replyToSender = replyToSender,
        replyToMessageId = replyToMessageId
    )

    override suspend fun deleteMessage(
        userId: String,
        contactId: String,
        messageId: String
    ): Result<Unit> = store.deleteMessage(userId, contactId, messageId)

    override suspend fun markThreadRead(userId: String, contactId: String): Result<Unit> =
        store.markThreadRead(userId, contactId)

    override suspend fun toggleLockChat(
        userId: String,
        contactId: String,
        locked: Boolean
    ): Result<Unit> = store.toggleLockChat(userId, contactId, locked)

    override suspend fun toggleBlockChat(
        userId: String,
        contactId: String,
        blocked: Boolean
    ): Result<Unit> = store.toggleBlockChat(userId, contactId, blocked)

    override suspend fun toggleArchiveChat(
        userId: String,
        contactId: String,
        archived: Boolean
    ): Result<Unit> = store.toggleArchiveChat(userId, contactId, archived)

    override suspend fun toggleMuteChat(
        userId: String,
        contactId: String,
        muted: Boolean
    ): Result<Unit> = store.toggleMuteChat(userId, contactId, muted)

    override suspend fun setDisappearingMessages(
        userId: String,
        contactId: String,
        seconds: Long
    ): Result<Unit> = store.setDisappearingMessages(userId, contactId, seconds)

    override suspend fun setChatTheme(
        userId: String,
        contactId: String,
        themeKey: String
    ): Result<Unit> = store.setChatTheme(userId, contactId, themeKey)

    override suspend fun clearChatMessages(
        userId: String,
        contactId: String
    ): Result<Unit> = store.clearChatMessages(userId, contactId)

    override suspend fun renameChat(
        userId: String,
        contactId: String,
        newName: String
    ): Result<Unit> = store.renameChat(userId, contactId, newName)

    override suspend fun deleteChatPermanently(
        userId: String,
        contactId: String
    ): Result<Unit> = store.deleteChatPermanently(userId, contactId)

    override suspend fun reportUserAccount(
        userId: String,
        contact: ChatThread,
        reporterUsername: String
    ): Result<ReportOutcome> = store.reportUserAccount(userId, contact, reporterUsername)
}

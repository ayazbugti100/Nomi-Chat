package com.example.data.local

import android.content.Context
import com.example.data.model.BAN_REPORT_LIMIT
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.ReportOutcome
import com.example.data.model.STORY_DURATION_MS
import com.example.data.model.StoryItem
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
            if (findAccountKeyByUsernameUnlocked(root, user) != null) {
                return@withContext LocalAuthOutcome.Error("This username is already taken.")
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
                put("online", true)
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
            val accountKey = findAccountKeyByUsernameUnlocked(root, user)
                ?: return@withContext LocalAuthOutcome.Error("Invalid username or password.")
            val account = root.optJSONObject(accountKey)
                ?: return@withContext LocalAuthOutcome.Error("Invalid username or password.")
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
            if (account.optBoolean("online", false) == online) return@withLock
            account.put("online", online)
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
                                peerAccount.optBoolean("online", false)
                        } else {
                            false
                        }
                        val peerPhoto = if (thread.threadType == "chat" && peerAccount != null && !peerAccount.isNull("photoData")) {
                            peerAccount.optString("photoData").takeIf { it.isNotBlank() }
                        } else null
                        list.add(
                            thread.copy(
                                online = realOnline,
                                photoData = peerPhoto ?: thread.photoData
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

    suspend fun postStory(
        usernameKey: String,
        mediaData: String,
        caption: String = "",
        createdAtMs: Long = System.currentTimeMillis()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(mediaData.isNotBlank() && mediaData.length <= 240000) { "Story image is invalid or too large." }
            val cleanCaption = caption.trim().take(140)
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
                    put("caption", cleanCaption)
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
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(jsonToChatMessage(it)) }
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
        photoData: String? = null
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
                newUsername?.let { rawHandle ->
                    val nextUsername = NomiChatRepository.normalizeUsername(rawHandle).take(40)
                    require(nextUsername.isNotEmpty()) { "Invalid username." }
                    require(nextUsername.length in 2..40) { "Username must be between 2 and 40 characters." }
                    if (nextUsername != currentKey && findAccountKeyByUsernameUnlocked(root, nextUsername) != null) {
                        throw IllegalStateException("This username is already taken.")
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
                account.put("updatedAtSec", System.currentTimeMillis() / 1000L)

                val finalDisplayName = account.optString("displayName", "")
                val finalUsername = account.optString("username", currentKey)
                val finalPhotoData = if (account.isNull("photoData")) null else account.optString("photoData").takeIf { it.isNotBlank() }

                if (movedUsername != null) {
                    root.remove(currentKey)
                    root.put(movedUsername!!, account)
                } else {
                    root.put(currentKey, account)
                }

                // Propagate updated displayName/username/photoData to matching peer contacts on other local accounts
                val allAccountKeys = root.keys()
                while (allAccountKeys.hasNext()) {
                    val otherKey = allAccountKeys.next()
                    if (otherKey == finalUsername) continue
                    val otherAccount = root.optJSONObject(otherKey) ?: continue
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
                            if (finalPhotoData != null) {
                                c.put("photoData", finalPhotoData)
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

    suspend fun lookupUserByUsername(usernameInput: String): Result<DirectoryLookupResult?> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHandle = NomiChatRepository.exactUsernameOrNull(usernameInput) ?: return@runCatching null
            mutex.withLock {
                val root = readRootUnlocked()
                val key = findAccountKeyByUsernameUnlocked(root, cleanHandle) ?: return@runCatching null
                val obj = root.optJSONObject(key) ?: return@runCatching null
                if (obj.optString("accountStatus", "active") == "banned") return@runCatching null
                val storedUsername = obj.optString("username", key)
                if (!storedUsername.equals(cleanHandle, ignoreCase = true)) return@runCatching null
                val rawPhoto = if (obj.isNull("photoData")) null else obj.optString("photoData").takeIf { it.isNotBlank() }
                DirectoryLookupResult(
                    username = storedUsername,
                    displayName = obj.optString("displayName", storedUsername),
                    ownerUid = key,
                    photoData = rawPhoto
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
                val peerPhotoData: String?
                val finalName: String

                if (validType == "chat") {
                    cleanHandle = NomiChatRepository.exactUsernameOrNull(contactUsername)
                        ?: throw IllegalStateException("User not found")
                    val foundKey = findAccountKeyByUsernameUnlocked(root, cleanHandle)
                        ?: throw IllegalStateException("User not found")
                    if (foundKey == usernameKey) {
                        throw IllegalStateException("You cannot add your own username.")
                    }
                    val candidateObj = root.optJSONObject(foundKey)
                    if (candidateObj == null ||
                        candidateObj.optString("accountStatus", "active") == "banned" ||
                        !candidateObj.optString("username", foundKey).equals(cleanHandle, ignoreCase = true)
                    ) {
                        throw IllegalStateException("User not found")
                    }
                    resolvedPeerUid = foundKey
                    resolvedIsPeer = true
                    val registeredPeerDisplayName = candidateObj.optString("displayName").trim()
                    peerOnline = candidateObj.optBoolean("online", false)
                    peerPhotoData = if (candidateObj.isNull("photoData")) null
                        else candidateObj.optString("photoData").takeIf { it.isNotBlank() }
                    finalName = registeredPeerDisplayName.ifBlank { cleanHandle }.take(60)
                } else {
                    cleanHandle = NomiChatRepository.normalizeUsername(rawHandle.ifBlank { name })
                        .ifBlank { "nomigroup" }.take(40)
                    resolvedPeerUid = peerUid
                    resolvedIsPeer = isRegisteredPeer
                    peerOnline = false
                    peerPhotoData = null
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
                        if (peerPhotoData != null) {
                            existingObj.put("photoData", peerPhotoData)
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
                        if (peerPhotoData != null) put("photoData", peerPhotoData) else put("photoData", JSONObject.NULL)
                        put("online", peerOnline)
                        put("typing", false)
                        put("unreadCount", 0L)
                        put("lastMessageText", "")
                        put("lastMessageFromMe", false)
                        put("lastMessageTimeLabel", "")
                        put("pinned", false)
                        put("locked", false)
                        put("blocked", false)
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
        incrementUnreadIfThem: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val root = readRootUnlocked()
                val account = root.optJSONObject(usernameKey)
                    ?: throw IllegalStateException("Account not found.")
                val cleanText = text.trim().take(2000)
                require(cleanText.isNotEmpty()) { "Message cannot be empty." }
                val validSender = if (sender == "them") "them" else "me"
                val timeLabel = currentFormattedTime()
                val nowSec = System.currentTimeMillis() / 1000L

                val messagesObj = account.optJSONObject("messages") ?: JSONObject()
                val msgArr = messagesObj.optJSONArray(contactId) ?: JSONArray()
                msgArr.put(
                    JSONObject().apply {
                        put("id", "m_${UUID.randomUUID().toString().take(8)}")
                        put("userId", usernameKey)
                        put("contactId", contactId)
                        put("sender", validSender)
                        put("text", cleanText)
                        put("timeLabel", timeLabel)
                        put("aiGenerated", aiGenerated)
                        put("createdAtSec", nowSec)
                    }
                )
                messagesObj.put(contactId, msgArr)
                account.put("messages", messagesObj)

                var targetPeerHandle = ""
                var targetThreadType = "chat"
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
                account.put("contacts", contacts)
                root.put(usernameKey, account)

                // Cross-account local delivery if target handle is another registered local account
                if (validSender == "me" && targetThreadType == "chat" && targetPeerHandle.isNotBlank()) {
                    val peerKey = findAccountKeyByUsernameUnlocked(root, targetPeerHandle)
                    if (peerKey != null && peerKey != usernameKey) {
                        val peerAccount = root.optJSONObject(peerKey)
                        if (peerAccount != null && peerAccount.optString("accountStatus", "active") != "banned") {
                            val senderPhoto = if (account.isNull("photoData")) null
                                else account.optString("photoData").takeIf { it.isNotBlank() }
                            deliverToLocalPeerUnlocked(
                                peerKey = peerKey,
                                peerAccount = peerAccount,
                                senderHandle = account.optString("username", usernameKey),
                                senderDisplayName = account.optString("displayName", usernameKey),
                                senderPhotoData = senderPhoto,
                                senderKey = usernameKey,
                                text = cleanText,
                                timeLabel = timeLabel,
                                nowSec = nowSec
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
        senderPhotoData: String?,
        senderKey: String,
        text: String,
        timeLabel: String,
        nowSec: Long
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

        if (peerContactObj == null) {
            peerContactId = "c_${UUID.randomUUID().toString().take(8)}"
            val newContact = JSONObject().apply {
                put("id", peerContactId)
                put("userId", peerKey)
                put("name", senderDisplayName)
                put("username", senderHandle)
                put("phone", "@$senderHandle")
                put("threadType", "chat")
                if (senderPhotoData != null) put("photoData", senderPhotoData) else put("photoData", JSONObject.NULL)
                put("online", false)
                put("typing", false)
                put("unreadCount", 1L)
                put("lastMessageText", text.take(1000))
                put("lastMessageFromMe", false)
                put("lastMessageTimeLabel", timeLabel)
                put("pinned", false)
                put("locked", false)
                put("blocked", false)
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
            if (senderPhotoData != null) {
                peerContactObj.put("photoData", senderPhotoData)
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
                put("timeLabel", timeLabel)
                put("aiGenerated", false)
                put("createdAtSec", nowSec)
            }
        )
        peerMessagesObj.put(peerContactId, peerMsgArr)
        peerAccount.put("messages", peerMessagesObj)
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
                    "${contact.name} has been auto-banned ($updatedCount/$BAN_REPORT_LIMIT reports)."
                } else {
                    "Reported ${contact.name} ($updatedCount/$BAN_REPORT_LIMIT reports)."
                }
                ReportOutcome(ok = true, message = feedback, status = updatedStatus, count = updatedCount)
            }
        }
    }

    private fun jsonToUserProfile(obj: JSONObject): UserProfile {
        val createdSec = obj.optLong("createdAtSec", 0L)
        val updatedSec = obj.optLong("updatedAtSec", 0L)
        val rawPhoto = if (obj.isNull("photoData")) null else obj.optString("photoData").takeIf { it.isNotBlank() }
        return UserProfile(
            id = obj.optString("userId", ""),
            userId = obj.optString("userId", ""),
            displayName = obj.optString("displayName", ""),
            username = obj.optString("username", ""),
            bio = obj.optString("bio", "Dream Big... 💚"),
            dob = obj.optString("dob", "2000-01-01"),
            provider = obj.optString("provider", "username"),
            photoData = rawPhoto,
            online = obj.optBoolean("online", false),
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
        return ChatThread(
            id = obj.optString("id", ""),
            userId = obj.optString("userId", ""),
            name = obj.optString("name", ""),
            username = obj.optString("username", ""),
            phone = obj.optString("phone", ""),
            threadType = obj.optString("threadType", "chat"),
            photoData = rawPhoto,
            online = obj.optBoolean("online", false),
            typing = obj.optBoolean("typing", false),
            unreadCount = obj.optLong("unreadCount", 0L),
            lastMessageText = obj.optString("lastMessageText", ""),
            lastMessageFromMe = obj.optBoolean("lastMessageFromMe", false),
            lastMessageTimeLabel = obj.optString("lastMessageTimeLabel", ""),
            pinned = obj.optBoolean("pinned", false),
            locked = obj.optBoolean("locked", false),
            blocked = obj.optBoolean("blocked", false),
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
        return StoryItem(
            id = obj.optString("id", ""),
            userId = obj.optString("userId", ""),
            authorName = obj.optString("authorName", fallbackName).ifBlank { fallbackName },
            authorUsername = obj.optString("authorUsername", fallbackUsername).ifBlank { fallbackUsername },
            authorPhotoData = fallbackPhoto ?: rawAuthorPhoto,
            mediaData = obj.optString("mediaData", ""),
            caption = obj.optString("caption", ""),
            createdAtMs = createdMs,
            expiresAtMs = obj.optLong("expiresAtMs", createdMs + STORY_DURATION_MS),
            createdAt = Timestamp(createdMs / 1000L, 0)
        )
    }

    private fun jsonToChatMessage(obj: JSONObject): ChatMessage {
        return ChatMessage(
            id = obj.optString("id", ""),
            userId = obj.optString("userId", ""),
            contactId = obj.optString("contactId", ""),
            sender = obj.optString("sender", "me"),
            text = obj.optString("text", ""),
            timeLabel = obj.optString("timeLabel", ""),
            aiGenerated = obj.optBoolean("aiGenerated", false),
            createdAt = Timestamp(obj.optLong("createdAtSec", 0L), 0)
        )
    }

    private fun currentFormattedTime(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
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

    override suspend fun postStory(
        userId: String,
        mediaData: String,
        caption: String
    ): Result<String> = store.postStory(userId, mediaData, caption)

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
        photoData: String?
    ): Result<String?> = store.updateProfile(userId, displayName, username, bio, dob, photoData)

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

    override suspend fun lookupUserByUsername(username: String): Result<DirectoryLookupResult?> =
        store.lookupUserByUsername(username)

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
        incrementUnreadIfThem: Boolean
    ): Result<Unit> = store.sendMessage(userId, contactId, sender, text, aiGenerated, incrementUnreadIfThem)

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

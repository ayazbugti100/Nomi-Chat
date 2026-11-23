package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AuthFlowMode
import com.example.data.model.BAN_REPORT_LIMIT
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.ReportOutcome
import com.example.data.model.UserDirectoryEntry
import com.example.data.model.UserProfile
import com.example.data.model.UserSetupOutcome
import com.example.data.security.PasswordHasher
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NomiChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) : NomiDataSource {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    override val requiresInitialCloudSetup: Boolean = true

    override suspend fun onNetworkReconnected() {
        runCatching { db.enableNetwork().await() }
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun sanitizeHandle(raw: String): String {
        val cleaned = normalizeUsername(raw)
        return cleaned.ifBlank { "nomiuser" }.take(40)
    }

    override fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeDirectoryStatus(username: String): Flow<UserDirectoryEntry?> = flow {
        requireUserId()
        val handle = sanitizeHandle(username)
        val docRef = db.collection("directory").document(handle)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserDirectoryEntry::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser != null) {
                        handleFirestoreError(toLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    override fun observeContacts(userId: String): Flow<List<ChatThread>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        val path = "users/$uid/contacts"
        val contactsFlow = db.collection("users").document(uid).collection("contacts")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ChatThread::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val toLog = firestoreError ?: (error as? Exception)
                if (toLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(toLog, OperationType.LIST, path)
                }
                throw error
            }

        emitAll(
            channelFlow {
                val latestContacts = MutableStateFlow<List<ChatThread>?>(null)
                val directoryEntries = MutableStateFlow<Map<String, UserDirectoryEntry>>(emptyMap())
                val trackedHandles = MutableStateFlow<List<String>>(emptyList())

                launch {
                    contactsFlow.collect { threads ->
                        latestContacts.value = threads
                        val peerHandles = threads
                            .asSequence()
                            .filter {
                                it.threadType == "chat" &&
                                    (it.isRegisteredPeer || it.peerUid.isNotBlank()) &&
                                    it.username.isNotBlank()
                            }
                            .map { sanitizeHandle(it.username) }
                            .distinct()
                            .sorted()
                            .take(30)
                            .toList()
                        if (peerHandles != trackedHandles.value) {
                            trackedHandles.value = peerHandles
                        }
                    }
                }

                launch {
                    trackedHandles.collectLatest { handles ->
                        if (handles.isEmpty()) {
                            directoryEntries.value = emptyMap()
                            return@collectLatest
                        }
                        coroutineScope {
                            for (handle in handles) {
                                launch {
                                    db.collection("directory").document(handle)
                                        .snapshots()
                                        .catch { err ->
                                            if (err is CancellationException) throw err
                                        }
                                        .collect { snap ->
                                            if (snap.exists()) {
                                                val entry = snap.toObject(
                                                    UserDirectoryEntry::class.java,
                                                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                                                )
                                                if (entry != null) {
                                                    directoryEntries.update { current ->
                                                        current + (handle to entry)
                                                    }
                                                }
                                            }
                                        }
                                }
                            }
                        }
                    }
                }

                combine(latestContacts, directoryEntries) { contacts, dirMap ->
                    contacts?.map { thread ->
                        val entry = if (thread.threadType == "chat" && thread.username.isNotBlank()) {
                            dirMap[sanitizeHandle(thread.username)]
                        } else null
                        mergeThreadWithDirectory(thread, entry)
                    }
                }.collect { merged ->
                    if (merged != null) {
                        send(merged)
                    }
                }
            }
        )
    }

    override fun observeMessages(userId: String, contactId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        require(contactId.isNotBlank()) { "contactId cannot be blank." }
        val path = "users/$uid/contacts/$contactId/messages"
        emitAll(
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    override suspend fun ensureInitialUserSetup(
        userId: String,
        googleDisplayName: String?,
        googleEmail: String?,
        preferredDisplayName: String,
        preferredUsername: String,
        preferredDob: String,
        authFlowMode: AuthFlowMode,
        returningIdentifier: String,
        pendingPassword: String
    ): Result<UserSetupOutcome> {
        val userDocPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user changed." }

            val userRef = db.collection("users").document(uid)
            val existingUser = userRef.get().await()

            if (existingUser.exists()) {
                val status = existingUser.getString("accountStatus") ?: "active"
                if (status == "banned") {
                    return Result.success(UserSetupOutcome.Banned)
                }
                val existingHandle = sanitizeHandle(existingUser.getString("username").orEmpty())
                val existingDisplayName = existingUser.getString("displayName").orEmpty()
                if (existingHandle.isNotBlank()) {
                    val existingDirSnap = db.collection("directory").document(existingHandle).get().await()
                    if (existingDirSnap.exists() && existingDirSnap.getString("accountStatus") == "banned") {
                        return Result.success(UserSetupOutcome.Banned)
                    }
                }

                when (authFlowMode) {
                    AuthFlowMode.GOOGLE_QUICK -> {
                        return Result.success(UserSetupOutcome.Allowed)
                    }
                    AuthFlowMode.RETURNING_LOGIN -> {
                        val cleanUser = normalizeUsername(returningIdentifier)
                        if (cleanUser.isEmpty() || pendingPassword.isEmpty()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Enter both username and password.")
                            )
                        }
                        if (cleanUser.length < 2) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Invalid username or password.")
                            )
                        }

                        val credSnap = userRef.collection("credentials").document(cleanUser).get().await()
                        if (!credSnap.exists()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Invalid username or password.")
                            )
                        }
                        val salt = credSnap.getString("passwordSalt").orEmpty()
                        val expectedHash = credSnap.getString("passwordHash").orEmpty()
                        if (!PasswordHasher.verifyPassword(pendingPassword, salt, expectedHash)) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Invalid username or password.")
                            )
                        }
                        val credStatus = credSnap.getString("accountStatus") ?: "active"
                        val dirSnap = db.collection("directory").document(cleanUser).get().await()
                        if (credStatus == "banned" ||
                            (dirSnap.exists() && dirSnap.getString("accountStatus") == "banned")
                        ) {
                            return Result.success(UserSetupOutcome.Banned)
                        }

                        val restoredName = credSnap.getString("displayName").orEmpty().ifBlank { existingDisplayName }
                        val restoredDob = credSnap.getString("dob").orEmpty().ifBlank { "2000-01-01" }
                        if (cleanUser != existingHandle || restoredName != existingDisplayName) {
                            userRef.update(
                                mapOf(
                                    "username" to cleanUser,
                                    "displayName" to restoredName,
                                    "dob" to restoredDob,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                        }
                        return Result.success(UserSetupOutcome.Allowed)
                    }
                    AuthFlowMode.SIGN_UP -> {
                        val cleanName = preferredDisplayName.trim().take(60)
                        val cleanUser = normalizeUsername(preferredUsername).take(40)
                        val cleanDob = preferredDob.trim().take(20)
                        if (cleanName.isBlank() || cleanUser.isBlank() || cleanDob.isBlank() || pendingPassword.isEmpty()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Please fill in all fields.")
                            )
                        }
                        if (cleanUser.length < 2) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Username must be at least 2 characters.")
                            )
                        }

                        val requestedDirRef = db.collection("directory").document(cleanUser)
                        val requestedDirSnap = requestedDirRef.get().await()
                        if (requestedDirSnap.exists()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("This username is already taken.")
                            )
                        }

                        val salt = PasswordHasher.generateSalt()
                        val hash = PasswordHasher.hashPassword(pendingPassword, salt)
                        val credRef = userRef.collection("credentials").document(cleanUser)

                        val batch = db.batch()
                        batch.update(
                            userRef,
                            mapOf(
                                "displayName" to cleanName,
                                "username" to cleanUser,
                                "dob" to cleanDob,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        batch.set(
                            credRef,
                            mapOf(
                                "userId" to uid,
                                "username" to cleanUser,
                                "displayName" to cleanName,
                                "dob" to cleanDob,
                                "passwordSalt" to salt,
                                "passwordHash" to hash,
                                "accountStatus" to "active",
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        batch.set(
                            requestedDirRef,
                            mapOf(
                                "ownerUid" to uid,
                                "username" to cleanUser,
                                "displayName" to cleanName,
                                "provider" to (existingUser.getString("provider") ?: "username"),
                                "accountStatus" to "active",
                                "reportCount" to 0L,
                                "reportedByUids" to emptyList<String>(),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        batch.commit().await()
                        return Result.success(UserSetupOutcome.Allowed)
                    }
                }
            }

            // No /users/{uid} profile yet
            if (authFlowMode == AuthFlowMode.RETURNING_LOGIN) {
                val cleanUser = normalizeUsername(returningIdentifier)
                if (cleanUser.isEmpty() || pendingPassword.isEmpty()) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Enter both username and password.")
                    )
                }
                if (cleanUser.length < 2) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Invalid username or password.")
                    )
                }
                val credSnap = userRef.collection("credentials").document(cleanUser).get().await()
                if (!credSnap.exists()) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Invalid username or password.")
                    )
                }
                val salt = credSnap.getString("passwordSalt").orEmpty()
                val expectedHash = credSnap.getString("passwordHash").orEmpty()
                if (!PasswordHasher.verifyPassword(pendingPassword, salt, expectedHash)) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Invalid username or password.")
                    )
                }
                val dirSnap = db.collection("directory").document(cleanUser).get().await()
                if (credSnap.getString("accountStatus") == "banned" ||
                    (dirSnap.exists() && dirSnap.getString("accountStatus") == "banned")
                ) {
                    return Result.success(UserSetupOutcome.Banned)
                }
                val restoredName = credSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
                val restoredDob = credSnap.getString("dob").orEmpty().ifBlank { "2000-01-01" }.take(20)
                createFreshUserProfileWithStarters(
                    uid = uid,
                    userRef = userRef,
                    resolvedName = restoredName,
                    resolvedUsername = cleanUser,
                    resolvedDob = restoredDob,
                    resolvedProvider = "username",
                    dirRef = null
                )
                return Result.success(UserSetupOutcome.Allowed)
            }

            if (authFlowMode == AuthFlowMode.SIGN_UP) {
                val cleanName = preferredDisplayName.trim()
                val cleanUser = normalizeUsername(preferredUsername)
                val cleanDob = preferredDob.trim()
                if (cleanName.isBlank() || cleanUser.isBlank() || cleanDob.isBlank() || pendingPassword.isEmpty()) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Please fill in all fields.")
                    )
                }
                if (cleanUser.length < 2) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Username must be at least 2 characters.")
                    )
                }
            }

            val resolvedName = preferredDisplayName.trim()
                .ifBlank { googleDisplayName?.trim().orEmpty() }
                .ifBlank { "Nomi User" }
                .take(60)

            val desiredHandleRaw = if (authFlowMode == AuthFlowMode.SIGN_UP) {
                normalizeUsername(preferredUsername)
            } else {
                normalizeUsername(preferredUsername)
                    .ifBlank {
                        normalizeUsername(
                            googleEmail?.substringBefore("@").orEmpty()
                                .ifBlank { resolvedName }
                        )
                    }
                    .ifBlank { "nomiuser" }
            }
            val baseHandle = if (desiredHandleRaw.length >= 2) desiredHandleRaw.take(40) else "nomi_$desiredHandleRaw"

            val dirRefCandidate = db.collection("directory").document(baseHandle)
            val dirSnapCandidate = dirRefCandidate.get().await()

            val resolvedUsername: String
            val dirRefToCreate: com.google.firebase.firestore.DocumentReference?

            if (dirSnapCandidate.exists()) {
                if (dirSnapCandidate.getString("accountStatus") == "banned" &&
                    dirSnapCandidate.getString("ownerUid") == uid
                ) {
                    return Result.success(UserSetupOutcome.Banned)
                }
                if (authFlowMode == AuthFlowMode.SIGN_UP) {
                    return Result.success(
                        UserSetupOutcome.Rejected("This username is already taken.")
                    )
                } else if (dirSnapCandidate.getString("ownerUid") == uid) {
                    resolvedUsername = baseHandle
                    dirRefToCreate = null
                } else {
                    val fallbackHandle = "${baseHandle.take(30)}_${uid.take(6).lowercase()}"
                    val fallbackRef = db.collection("directory").document(fallbackHandle)
                    val fallbackSnap = fallbackRef.get().await()
                    if (fallbackSnap.exists() && fallbackSnap.getString("ownerUid") != uid) {
                        val uniqueHandle = "${baseHandle.take(24)}_${(System.currentTimeMillis() % 100000)}"
                        resolvedUsername = uniqueHandle
                        dirRefToCreate = db.collection("directory").document(uniqueHandle)
                    } else {
                        resolvedUsername = fallbackHandle
                        dirRefToCreate = if (fallbackSnap.exists()) null else fallbackRef
                    }
                }
            } else {
                resolvedUsername = baseHandle
                dirRefToCreate = dirRefCandidate
            }

            val resolvedDob = preferredDob.trim().ifBlank { "2000-01-01" }.take(20)
            val resolvedProvider = if (authFlowMode == AuthFlowMode.SIGN_UP) "username" else "google"
            val credPair = if (authFlowMode == AuthFlowMode.SIGN_UP && pendingPassword.isNotEmpty()) {
                val salt = PasswordHasher.generateSalt()
                val hash = PasswordHasher.hashPassword(pendingPassword, salt)
                salt to hash
            } else null

            createFreshUserProfileWithStarters(
                uid = uid,
                userRef = userRef,
                resolvedName = resolvedName,
                resolvedUsername = resolvedUsername,
                resolvedDob = resolvedDob,
                resolvedProvider = resolvedProvider,
                dirRef = dirRefToCreate,
                credentialSaltAndHash = credPair
            )

            Result.success(UserSetupOutcome.Allowed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDocPath)
            Result.failure(e)
        }
    }

    private suspend fun createFreshUserProfileWithStarters(
        uid: String,
        userRef: com.google.firebase.firestore.DocumentReference,
        resolvedName: String,
        resolvedUsername: String,
        resolvedDob: String,
        resolvedProvider: String,
        dirRef: com.google.firebase.firestore.DocumentReference?,
        credentialSaltAndHash: Pair<String, String>? = null
    ) {
        val batch = db.batch()
        val profilePayload = mapOf(
            "userId" to uid,
            "displayName" to resolvedName,
            "username" to resolvedUsername,
            "bio" to "Dream Big... 💚",
            "dob" to resolvedDob,
            "provider" to resolvedProvider,
            "online" to true,
            "verified" to true,
            "premiumUnlocked" to true,
            "accountStatus" to "active",
            "reportCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(userRef, profilePayload)

        if (credentialSaltAndHash != null) {
            val credRef = userRef.collection("credentials").document(resolvedUsername)
            batch.set(
                credRef,
                mapOf(
                    "userId" to uid,
                    "username" to resolvedUsername,
                    "displayName" to resolvedName,
                    "dob" to resolvedDob,
                    "passwordSalt" to credentialSaltAndHash.first,
                    "passwordHash" to credentialSaltAndHash.second,
                    "accountStatus" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        if (dirRef != null) {
            batch.set(
                dirRef,
                mapOf(
                    "ownerUid" to uid,
                    "username" to resolvedUsername,
                    "displayName" to resolvedName,
                    "provider" to resolvedProvider,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "reportedByUids" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        val starters = listOf(
            StarterThread("c_alex", "Alex Morgan", "alexmorgan", "+1 555 234 5678", "chat", true, 1L, "Hi! How are you?", "9:41 PM", true),
            StarterThread("c_friends", "Friends Group", "friends_grp", "Group", "group", true, 12L, "Best video ever! 🎬", "8:52 PM", false),
            StarterThread("c_sara", "Sara Khan", "sarakhan", "+92 321 9876543", "chat", true, 1L, "Okay 👍", "7:30 PM", false),
            StarterThread("c_family", "Family Group", "family_grp", "Group", "group", false, 3L, "Dinner ready!", "6:20 PM", false),
            StarterThread("c_rizwan", "Rizwan Ali", "rizwan_ali", "+92 333 5554433", "chat", false, 0L, "Call me later", "5:15 PM", false),
            StarterThread("c_besties", "Best Friends", "best_friends", "Group", "group", true, 0L, "See you soon ❤️", "4:40 PM", false),
            StarterThread("c_news", "News Channel", "nomi_news", "Channel", "group", true, 0L, "Big update today!", "3:25 PM", false)
        )

        starters.forEach { starter ->
            val contactRef = userRef.collection("contacts").document(starter.id)
            batch.set(
                contactRef,
                mapOf(
                    "userId" to uid,
                    "name" to starter.name,
                    "username" to starter.username,
                    "phone" to starter.phone,
                    "threadType" to starter.type,
                    "online" to starter.online,
                    "typing" to false,
                    "unreadCount" to starter.unread,
                    "lastMessageText" to starter.message,
                    "lastMessageFromMe" to false,
                    "lastMessageTimeLabel" to starter.time,
                    "pinned" to starter.pinned,
                    "locked" to false,
                    "blocked" to false,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "reportedBy" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            val msgRef = contactRef.collection("messages").document("m_init_${starter.id}")
            batch.set(
                msgRef,
                mapOf(
                    "userId" to uid,
                    "contactId" to starter.id,
                    "sender" to "them",
                    "text" to starter.message,
                    "timeLabel" to starter.time,
                    "aiGenerated" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }

        batch.commit().await()
    }

    override suspend fun updateProfile(
        userId: String,
        displayName: String?,
        username: String?,
        bio: String?,
        dob: String?,
        photoData: String?
    ): Result<String?> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }

            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            displayName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                require(it.length <= 60) { "Display name must be 60 characters or fewer." }
                updates["displayName"] = it
            }
            username?.let { rawHandle ->
                val clean = normalizeUsername(rawHandle)
                require(clean.length in 2..40) { "Username must be between 2 and 40 characters." }
                val existingDir = db.collection("directory").document(clean).get().await()
                if (existingDir.exists() && existingDir.getString("ownerUid") != uid) {
                    return Result.failure(
                        IllegalStateException("Username \"@$clean\" is already taken. Please choose another username.")
                    )
                }
                updates["username"] = clean
            }
            bio?.trim()?.let {
                require(it.length <= 200) { "Bio must be 200 characters or fewer." }
                updates["bio"] = it
            }
            dob?.trim()?.takeIf { it.isNotEmpty() }?.let {
                require(it.length in 4..20) { "Date of birth must be formatted cleanly (e.g., 2000-01-01)." }
                updates["dob"] = it
            }
            photoData?.let {
                require(it.length <= 150000) { "Profile photo is too large." }
                updates["photoData"] = it
            }

            val userRef = db.collection("users").document(uid)
            val currentUserSnap = userRef.get().await()
            val currentHandle = currentUserSnap.getString("username").orEmpty()
            val currentDisplayName = currentUserSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }
            val currentProvider = currentUserSnap.getString("provider").orEmpty().ifBlank { "google" }

            userRef.update(updates).await()

            // Keep public directory entry synced whenever displayName or username changes
            val updatedHandle = (updates["username"] as? String) ?: currentHandle
            val updatedName = (updates["displayName"] as? String) ?: currentDisplayName
            if (updatedHandle.isNotBlank() && updatedName.isNotBlank()) {
                val dirRef = db.collection("directory").document(updatedHandle)
                val dirSnap = dirRef.get().await()
                if (!dirSnap.exists()) {
                    dirRef.set(
                        mapOf(
                            "ownerUid" to uid,
                            "username" to updatedHandle,
                            "displayName" to updatedName.take(60),
                            "provider" to currentProvider,
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } else if (dirSnap.getString("ownerUid") == uid) {
                    dirRef.update(
                        mapOf(
                            "displayName" to updatedName.take(60),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            Result.success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun lookupUserByUsername(username: String): Result<DirectoryLookupResult?> {
        val cleanHandle = sanitizeHandle(username.trim().removePrefix("@"))
        if (cleanHandle.length < 2) return Result.success(null)
        val path = "directory/$cleanHandle"
        return try {
            requireUserId()
            val snap = db.collection("directory").document(cleanHandle).get().await()
            if (!snap.exists()) return Result.success(null)
            if (snap.getString("accountStatus") == "banned") return Result.success(null)
            val ownerUid = snap.getString("ownerUid").orEmpty()
            val displayName = snap.getString("displayName").orEmpty().ifBlank { cleanHandle }
            val handle = snap.getString("username").orEmpty().ifBlank { cleanHandle }
            Result.success(
                DirectoryLookupResult(
                    username = handle,
                    displayName = displayName,
                    ownerUid = ownerUid
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    override suspend fun addContact(
        userId: String,
        name: String,
        username: String,
        phone: String,
        threadType: String,
        peerUid: String,
        isRegisteredPeer: Boolean
    ): Result<String> {
        var path = "users/$userId/contacts"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }

            val rawHandle = username.trim().removePrefix("@")
            val cleanHandle = sanitizeHandle(rawHandle.ifBlank { name })
            val derivedName = rawHandle
                .replace(Regex("[_.-]+"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
            val cleanName = name.trim().ifBlank { derivedName }.ifBlank { cleanHandle }.take(60)
            require(cleanName.isNotEmpty()) { "Contact name or username cannot be empty." }
            val cleanPhone = phone.trim().ifBlank { "@$cleanHandle" }.take(30)
            val validType = if (threadType == "group") "group" else "chat"
            var resolvedPeerUid = peerUid
            var resolvedIsPeer = isRegisteredPeer
            var resolvedDirName = ""
            if (validType == "chat" && resolvedPeerUid.isBlank() && cleanHandle.length >= 2) {
                val dirSnap = runCatching { db.collection("directory").document(cleanHandle).get().await() }.getOrNull()
                if (dirSnap != null && dirSnap.exists() && dirSnap.getString("accountStatus") != "banned") {
                    val owner = dirSnap.getString("ownerUid").orEmpty()
                    if (owner.isNotBlank() && owner != uid) {
                        resolvedPeerUid = owner
                        resolvedIsPeer = true
                        resolvedDirName = dirSnap.getString("displayName").orEmpty()
                    }
                }
            }
            val finalName = name.trim()
                .ifBlank { resolvedDirName }
                .ifBlank { derivedName }
                .ifBlank { cleanHandle }
                .take(60)
            require(finalName.isNotEmpty()) { "Contact name or username cannot be empty." }

            val nowLabel = currentFormattedTime()
            val greeting = "Hi! I'm $finalName, connected on Nomi Chat!"

            val contactRef = db.collection("users").document(uid).collection("contacts").document()
            path = contactRef.path
            val msgRef = contactRef.collection("messages").document()

            val batch = db.batch()
            batch.set(
                contactRef,
                mapOf(
                    "userId" to uid,
                    "name" to finalName,
                    "username" to cleanHandle,
                    "phone" to cleanPhone,
                    "threadType" to validType,
                    "online" to true,
                    "typing" to false,
                    "unreadCount" to 0L,
                    "lastMessageText" to greeting,
                    "lastMessageFromMe" to false,
                    "lastMessageTimeLabel" to nowLabel,
                    "pinned" to false,
                    "locked" to false,
                    "blocked" to false,
                    "peerUid" to resolvedPeerUid,
                    "isRegisteredPeer" to resolvedIsPeer,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "reportedBy" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                msgRef,
                mapOf(
                    "userId" to uid,
                    "contactId" to contactRef.id,
                    "sender" to "them",
                    "text" to greeting,
                    "timeLabel" to nowLabel,
                    "aiGenerated" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(contactRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun setContactTyping(
        userId: String,
        contactId: String,
        isTyping: Boolean
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "typing" to isTyping,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun sendMessage(
        userId: String,
        contactId: String,
        sender: String,
        text: String,
        aiGenerated: Boolean,
        incrementUnreadIfThem: Boolean
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId/messages"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val cleanText = text.trim().take(2000)
            require(cleanText.isNotEmpty()) { "Message cannot be empty." }
            val validSender = if (sender == "them") "them" else "me"
            val timeLabel = currentFormattedTime()

            val userRef = db.collection("users").document(uid)
            val contactRef = userRef.collection("contacts").document(contactId)
            val msgRef = contactRef.collection("messages").document()

            val contactSnap = contactRef.get().await()
            var peerUid = contactSnap.getString("peerUid").orEmpty()
            val peerUsername = contactSnap.getString("username").orEmpty()
            val threadType = contactSnap.getString("threadType").orEmpty()
            var resolvedPeerName: String? = null
            var linkedNewly = false

            if (validSender == "me" && threadType != "group" && peerUid.isBlank() && peerUsername.isNotBlank()) {
                val cleanPeerHandle = sanitizeHandle(peerUsername)
                val dirSnap = runCatching {
                    db.collection("directory").document(cleanPeerHandle).get().await()
                }.getOrNull()
                if (dirSnap != null && dirSnap.exists() && dirSnap.getString("accountStatus") != "banned") {
                    val owner = dirSnap.getString("ownerUid").orEmpty()
                    if (owner.isNotBlank() && owner != uid) {
                        peerUid = owner
                        resolvedPeerName = dirSnap.getString("displayName")?.takeIf { it.isNotBlank() }?.take(60)
                        linkedNewly = true
                    }
                }
            }

            val batch = db.batch()
            batch.set(
                msgRef,
                mapOf(
                    "userId" to uid,
                    "contactId" to contactId,
                    "sender" to validSender,
                    "text" to cleanText,
                    "timeLabel" to timeLabel,
                    "aiGenerated" to aiGenerated,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            val unreadUpdate: Any = if (validSender == "them" && incrementUnreadIfThem) {
                FieldValue.increment(1L)
            } else {
                0L
            }
            val contactUpdates = mutableMapOf<String, Any>(
                "lastMessageText" to cleanText.take(1000),
                "lastMessageFromMe" to (validSender == "me"),
                "lastMessageTimeLabel" to timeLabel,
                "unreadCount" to unreadUpdate,
                "online" to true,
                "typing" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (linkedNewly) {
                contactUpdates["peerUid"] = peerUid
                contactUpdates["isRegisteredPeer"] = true
                if (!resolvedPeerName.isNullOrBlank()) {
                    contactUpdates["name"] = resolvedPeerName
                }
            }
            batch.update(contactRef, contactUpdates)

            if (validSender == "me" && peerUid.isNotBlank() && peerUid != uid) {
                val myProfileSnap = userRef.get().await()
                val myUsername = myProfileSnap.getString("username").orEmpty().ifBlank { "nomiuser" }.take(40)
                val myDisplayName = myProfileSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
                val relayRef = db.collection("direct_messages").document()
                batch.set(
                    relayRef,
                    mapOf(
                        "fromUid" to uid,
                        "toUid" to peerUid,
                        "fromUsername" to myUsername,
                        "fromDisplayName" to myDisplayName,
                        "toUsername" to peerUsername.ifBlank { "nomiuser" }.take(40),
                        "text" to cleanText,
                        "timeLabel" to timeLabel,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun startIncomingSync(userId: String, activeContactIdProvider: () -> String?) {
        val uid = auth.currentUser?.uid ?: return
        if (uid != userId) return
        val processedDocIds = mutableSetOf<String>()

        db.collection("direct_messages")
            .whereEqualTo("toUid", uid)
            .snapshots()
            .retryWhen { error, attempt ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val toLog = firestoreError ?: (error as? Exception)
                if (toLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(toLog, OperationType.LIST, "direct_messages")
                }
                if (firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    false
                } else {
                    delay(minOf(15_000L, 1_000L * (attempt + 1)))
                    auth.currentUser?.uid == uid
                }
            }
            .collect { snapshot ->
                for (doc in snapshot.documents) {
                    if (!processedDocIds.add(doc.id)) {
                        continue
                    }
                    if (processedDocIds.size > 500) {
                        val iter = processedDocIds.iterator()
                        if (iter.hasNext()) {
                            iter.next()
                            iter.remove()
                        }
                    }

                    val fromUid = doc.getString("fromUid").orEmpty()
                    val fromUsername = sanitizeHandle(doc.getString("fromUsername").orEmpty())
                    val fromDisplayName = doc.getString("fromDisplayName").orEmpty().ifBlank { fromUsername }.take(60)
                    val text = doc.getString("text").orEmpty().take(2000)
                    val timeLabel = doc.getString("timeLabel").orEmpty().ifBlank { currentFormattedTime() }.take(30)
                    if (fromUid.isBlank() || text.isBlank()) {
                        runCatching { doc.reference.delete().await() }
                        continue
                    }

                    val delivered = runCatching {
                        val userRef = db.collection("users").document(uid)
                        val existingByUid = userRef.collection("contacts")
                            .whereEqualTo("peerUid", fromUid)
                            .limit(1)
                            .get()
                            .await()
                        val existingDoc = existingByUid.documents.firstOrNull() ?: userRef.collection("contacts")
                            .whereEqualTo("username", fromUsername)
                            .limit(1)
                            .get()
                            .await()
                            .documents
                            .firstOrNull()

                        if (existingDoc != null && existingDoc.getBoolean("blocked") == true) {
                            doc.reference.delete().await()
                            return@runCatching true
                        }

                        val batch = db.batch()
                        if (existingDoc == null) {
                            val newContactRef = userRef.collection("contacts").document()
                            val msgRef = newContactRef.collection("messages").document()
                            val isActiveOpen = activeContactIdProvider() == newContactRef.id
                            batch.set(
                                newContactRef,
                                mapOf(
                                    "userId" to uid,
                                    "name" to fromDisplayName,
                                    "username" to fromUsername,
                                    "phone" to "@$fromUsername",
                                    "threadType" to "chat",
                                    "online" to true,
                                    "typing" to false,
                                    "unreadCount" to if (isActiveOpen) 0L else 1L,
                                    "lastMessageText" to text.take(1000),
                                    "lastMessageFromMe" to false,
                                    "lastMessageTimeLabel" to timeLabel,
                                    "pinned" to false,
                                    "locked" to false,
                                    "blocked" to false,
                                    "peerUid" to fromUid,
                                    "isRegisteredPeer" to true,
                                    "accountStatus" to "active",
                                    "reportCount" to 0L,
                                    "reportedBy" to emptyList<String>(),
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                            batch.set(
                                msgRef,
                                mapOf(
                                    "userId" to uid,
                                    "contactId" to newContactRef.id,
                                    "sender" to "them",
                                    "text" to text,
                                    "timeLabel" to timeLabel,
                                    "aiGenerated" to false,
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                            )
                        } else {
                            val contactRef = existingDoc.reference
                            val msgRef = contactRef.collection("messages").document()
                            val isActiveOpen = activeContactIdProvider() == existingDoc.id
                            batch.set(
                                msgRef,
                                mapOf(
                                    "userId" to uid,
                                    "contactId" to existingDoc.id,
                                    "sender" to "them",
                                    "text" to text,
                                    "timeLabel" to timeLabel,
                                    "aiGenerated" to false,
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                            )
                            batch.update(
                                contactRef,
                                mapOf(
                                    "name" to fromDisplayName,
                                    "username" to fromUsername,
                                    "peerUid" to fromUid,
                                    "isRegisteredPeer" to true,
                                    "lastMessageText" to text.take(1000),
                                    "lastMessageFromMe" to false,
                                    "lastMessageTimeLabel" to timeLabel,
                                    "unreadCount" to if (isActiveOpen) 0L else FieldValue.increment(1L),
                                    "online" to true,
                                    "typing" to false,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                        batch.delete(doc.reference)
                        batch.commit().await()
                        true
                    }
                    if (delivered.isFailure) {
                        processedDocIds.remove(doc.id)
                    }
                }
            }
    }

    override suspend fun markThreadRead(userId: String, contactId: String): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "unreadCount" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun toggleLockChat(
        userId: String,
        contactId: String,
        locked: Boolean
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "locked" to locked,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun toggleBlockChat(
        userId: String,
        contactId: String,
        blocked: Boolean
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "blocked" to blocked,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun deleteChatPermanently(
        userId: String,
        contactId: String
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val userRef = db.collection("users").document(uid)
            val contactRef = userRef.collection("contacts").document(contactId)
            val msgSnaps = contactRef.collection("messages").get().await()

            val batch = db.batch()
            msgSnaps.documents.forEach { batch.delete(it.reference) }
            batch.delete(contactRef)
            batch.update(
                userRef,
                mapOf(
                    "deletedContactIds" to FieldValue.arrayUnion(contactId),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    /**
     * Reports a target contact / user account using unique reporter tracking and auto-banning at BAN_REPORT_LIMIT (10).
     */
    override suspend fun reportUserAccount(
        userId: String,
        contact: ChatThread,
        reporterUsername: String
    ): Result<ReportOutcome> {
        val contactPath = "users/$userId/contacts/${contact.id}"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val reporterKey = sanitizeHandle(reporterUsername.ifBlank { uid })

            val contactRef = db.collection("users").document(uid)
                .collection("contacts").document(contact.id)
            val snap = contactRef.get().await()
            val currentThread = snap.toObject(
                ChatThread::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: contact

            // 1. Prevent duplicate reports from the same user
            if (currentThread.reportedBy.contains(reporterKey)) {
                return Result.success(
                    ReportOutcome(
                        ok = false,
                        message = "You have already reported this account.",
                        status = currentThread.accountStatus,
                        count = currentThread.reportCount
                    )
                )
            }

            // 2. Record unique report and recalculate count
            val updatedReporters = (currentThread.reportedBy + reporterKey).distinct().take(50)
            val updatedCount = updatedReporters.size.toLong()
            // 3. Auto-ban once 10 unique users report
            val updatedStatus = if (updatedCount >= BAN_REPORT_LIMIT) "banned" else "active"

            contactRef.update(
                mapOf(
                    "reportedBy" to updatedReporters,
                    "reportCount" to updatedCount,
                    "accountStatus" to updatedStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            // Also record the unique UID report in the shared /directory/{username} collection
            val targetHandle = sanitizeHandle(contact.username.ifBlank { contact.name })
            val dirRef = db.collection("directory").document(targetHandle)
            val dirSnap = dirRef.get().await()
            var finalCount = updatedCount
            var finalStatus = updatedStatus

            if (!dirSnap.exists()) {
                dirRef.set(
                    mapOf(
                        "username" to targetHandle,
                        "displayName" to contact.name.take(60),
                        "accountStatus" to "active",
                        "reportCount" to 1L,
                        "reportedByUids" to listOf(uid),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val entry = dirSnap.toObject(
                    UserDirectoryEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (entry != null && !entry.reportedByUids.contains(uid) && entry.reportedByUids.size < 50) {
                    val dirReporters = entry.reportedByUids + uid
                    val dirCount = dirReporters.size.toLong()
                    val dirStatus = if (dirCount >= BAN_REPORT_LIMIT) "banned" else "active"
                    dirRef.update(
                        mapOf(
                            "reportedByUids" to dirReporters,
                            "reportCount" to dirCount,
                            "accountStatus" to dirStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    finalCount = maxOf(finalCount, dirCount)
                    if (dirStatus == "banned") finalStatus = "banned"
                } else if (entry != null) {
                    finalCount = maxOf(finalCount, entry.reportCount)
                    if (entry.accountStatus == "banned") finalStatus = "banned"
                }
            }

            val feedback = if (finalStatus == "banned") {
                "${contact.name} has been auto-banned ($finalCount/$BAN_REPORT_LIMIT reports)."
            } else {
                "Reported ${contact.name} ($finalCount/$BAN_REPORT_LIMIT reports)."
            }

            Result.success(
                ReportOutcome(
                    ok = true,
                    message = feedback,
                    status = finalStatus,
                    count = finalCount
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, contactPath)
            Result.failure(e)
        }
    }

    private fun currentFormattedTime(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    private data class StarterThread(
        val id: String,
        val name: String,
        val username: String,
        val phone: String,
        val type: String,
        val online: Boolean,
        val unread: Long,
        val message: String,
        val time: String,
        val pinned: Boolean
    )

    companion object {
        fun normalizeUsername(raw: String): String {
            return raw.trim()
                .lowercase()
                .replace(Regex("\\s+"), "")
                .replace(Regex("[^a-z0-9_-]"), "_")
                .trim('_')
        }

        fun mergeThreadWithDirectory(thread: ChatThread, entry: UserDirectoryEntry?): ChatThread {
            if (entry == null) return thread
            if (thread.peerUid.isNotBlank() &&
                !entry.ownerUid.isNullOrBlank() &&
                entry.ownerUid != thread.peerUid
            ) {
                return thread
            }
            val syncedName = entry.displayName.trim().ifBlank { thread.name }
            val syncedStatus = if (entry.accountStatus == "banned") "banned" else thread.accountStatus
            return thread.copy(
                name = syncedName,
                accountStatus = syncedStatus,
                peerUid = thread.peerUid.ifBlank { entry.ownerUid.orEmpty() },
                isRegisteredPeer = thread.isRegisteredPeer || !entry.ownerUid.isNullOrBlank()
            )
        }
    }
}

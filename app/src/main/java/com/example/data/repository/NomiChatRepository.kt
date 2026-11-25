package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AuthFlowMode
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
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import kotlinx.coroutines.withTimeoutOrNull
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

    private val _incomingAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 24)
    override val incomingAlerts: Flow<IncomingMessageAlert> = _incomingAlerts.asSharedFlow()

    override suspend fun onNetworkReconnected() {
        runCatching { db.enableNetwork().await() }
    }

    override suspend fun verifyActiveFirebaseConnection(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return withTimeoutOrNull(3500L) {
            runCatching {
                db.enableNetwork().await()
                db.collection("users").document(uid).get(Source.SERVER).await()
                true
            }.getOrDefault(false)
        } ?: false
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
                                it.threadType == "chat" && it.username.isNotBlank()
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
                        mergeThreadWithDirectory(
                            thread = thread,
                            entry = entry,
                            viewerUid = uid
                        )
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
                    val nowMs = System.currentTimeMillis()
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).filter { msg -> msg.expiresAtMs <= 0L || msg.expiresAtMs > nowMs }
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

    override fun observeStories(userId: String): Flow<List<StoryItem>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        val path = "stories"
        emitAll(
            db.collection("stories")
                .orderBy("createdAtMs", Query.Direction.DESCENDING)
                .limit(100)
                .snapshots()
                .map { snapshot ->
                    val nowMs = System.currentTimeMillis()
                    snapshot.toObjects(
                        StoryItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).filter { it.expiresAtMs > nowMs }
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

    override fun observeStoryViews(userId: String, storyId: String): Flow<List<StoryViewRecord>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        require(storyId.isNotBlank()) { "storyId cannot be blank." }
        val path = "stories/$storyId/views"
        emitAll(
            db.collection("stories")
                .document(storyId)
                .collection("views")
                .orderBy("viewedAtMs", Query.Direction.DESCENDING)
                .limit(200)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        StoryViewRecord::class.java,
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
    ): Result<String> {
        val storyRef = db.collection("stories").document()
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
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
            val userSnap = db.collection("users").document(uid).get().await()
            val authorName = userSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
            val authorUsername = sanitizeHandle(userSnap.getString("username").orEmpty())
            val authorPhotoData = userSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
            val nowMs = System.currentTimeMillis()
            val expiresAtMs = nowMs + STORY_DURATION_MS

            val payload = mutableMapOf<String, Any>(
                "userId" to uid,
                "authorName" to authorName,
                "authorUsername" to authorUsername,
                "mediaData" to mediaData,
                "mediaType" to cleanMediaType,
                "caption" to cleanCaption,
                "storyFont" to normalizedFont,
                "musicTag" to musicTag.trim().take(80),
                "overlayText" to overlayText.trim().take(140),
                "overlayColorHex" to overlayColorHex,
                "overlayYFraction" to overlayYFraction.coerceIn(0.0, 1.0),
                "stickersJson" to stickersJson.take(4000),
                "drawingJson" to drawingJson.take(24000),
                "cropMode" to cleanCrop,
                "rotationDegrees" to cleanRotation,
                "viewedByUids" to emptyList<String>(),
                "likedByUids" to emptyList<String>(),
                "createdAtMs" to nowMs,
                "expiresAtMs" to expiresAtMs,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (authorPhotoData != null) {
                payload["authorPhotoData"] = authorPhotoData
            }
            storyRef.set(payload).await()
            Result.success(storyRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, storyRef.path)
            Result.failure(e)
        }
    }

    override suspend fun recordStoryView(userId: String, storyId: String): Result<Unit> {
        val path = "stories/$storyId/views/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(storyId.isNotBlank()) { "storyId cannot be blank." }
            val storyRef = db.collection("stories").document(storyId)
            val storySnap = storyRef.get().await()
            if (!storySnap.exists()) return Result.success(Unit)
            val authorUid = storySnap.getString("userId").orEmpty()
            if (authorUid == uid) {
                // Story owner opening their own story does not count as an external view
                return Result.success(Unit)
            }

            val userSnap = db.collection("users").document(uid).get().await()
            val viewerName = userSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
            val viewerUsername = sanitizeHandle(userSnap.getString("username").orEmpty()).ifBlank { "nomiuser" }.take(40)
            val viewerPhoto = userSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }

            val viewRef = storyRef.collection("views").document(uid)
            val existingViewSnap = viewRef.get().await()
            val existingLiked = existingViewSnap.getBoolean("liked") ?: false
            val existingEmoji = existingViewSnap.getString("reactionEmoji").orEmpty()
            val existingViewedAt = existingViewSnap.getLong("viewedAtMs") ?: 0L
            val nowMs = System.currentTimeMillis()

            val viewPayload = mutableMapOf<String, Any>(
                "viewerUid" to uid,
                "viewerName" to viewerName,
                "viewerUsername" to viewerUsername,
                "liked" to existingLiked,
                "reactionEmoji" to existingEmoji,
                "viewedAtMs" to if (existingViewedAt > 0L) existingViewedAt else nowMs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewerPhoto != null) {
                viewPayload["viewerPhotoData"] = viewerPhoto
            }
            viewRef.set(viewPayload).await()

            @Suppress("UNCHECKED_CAST")
            val currentViewed = (storySnap.get("viewedByUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            if (!currentViewed.contains(uid) && currentViewed.size < 500) {
                storyRef.update("viewedByUids", FieldValue.arrayUnion(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun reactToStory(
        userId: String,
        storyId: String,
        liked: Boolean?,
        reactionEmoji: String?
    ): Result<Unit> {
        val path = "stories/$storyId/views/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(storyId.isNotBlank()) { "storyId cannot be blank." }
            val storyRef = db.collection("stories").document(storyId)
            val storySnap = storyRef.get().await()
            if (!storySnap.exists()) return Result.success(Unit)

            val userSnap = db.collection("users").document(uid).get().await()
            val viewerName = userSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
            val viewerUsername = sanitizeHandle(userSnap.getString("username").orEmpty()).ifBlank { "nomiuser" }.take(40)
            val viewerPhoto = userSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }

            val viewRef = storyRef.collection("views").document(uid)
            val existingViewSnap = viewRef.get().await()
            val nextLiked = liked ?: (existingViewSnap.getBoolean("liked") ?: false)
            val nextEmoji = (reactionEmoji ?: existingViewSnap.getString("reactionEmoji").orEmpty()).take(16)
            val existingViewedAt = existingViewSnap.getLong("viewedAtMs") ?: System.currentTimeMillis()

            val viewPayload = mutableMapOf<String, Any>(
                "viewerUid" to uid,
                "viewerName" to viewerName,
                "viewerUsername" to viewerUsername,
                "liked" to nextLiked,
                "reactionEmoji" to nextEmoji,
                "viewedAtMs" to existingViewedAt,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewerPhoto != null) {
                viewPayload["viewerPhotoData"] = viewerPhoto
            }
            viewRef.set(viewPayload).await()

            if (liked != null) {
                @Suppress("UNCHECKED_CAST")
                val currentLiked = (storySnap.get("likedByUids") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                if (liked && !currentLiked.contains(uid) && currentLiked.size < 500) {
                    storyRef.update("likedByUids", FieldValue.arrayUnion(uid)).await()
                } else if (!liked && currentLiked.contains(uid)) {
                    storyRef.update("likedByUids", FieldValue.arrayRemove(uid)).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun replyToStory(
        userId: String,
        story: StoryItem,
        replyText: String
    ): Result<Unit> {
        val cleanReply = replyText.trim().take(2000)
        if (cleanReply.isBlank()) return Result.success(Unit)
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val userRef = db.collection("users").document(uid)
            val contactsCol = userRef.collection("contacts")
            val existingByUid = if (story.userId.isNotBlank()) {
                contactsCol.whereEqualTo("peerUid", story.userId).limit(1).get().await().documents.firstOrNull()
            } else null
            val existingByHandle = existingByUid ?: if (story.authorUsername.isNotBlank()) {
                contactsCol.whereEqualTo("username", sanitizeHandle(story.authorUsername)).limit(1).get().await().documents.firstOrNull()
            } else null

            val targetContactId = existingByHandle?.id ?: addContact(
                userId = uid,
                name = story.authorName.ifBlank { story.authorUsername },
                username = story.authorUsername,
                phone = "",
                threadType = "chat",
                peerUid = story.userId,
                isRegisteredPeer = story.userId.isNotBlank()
            ).getOrThrow()

            val storySnippet = when {
                story.caption.isNotBlank() -> "Story: ${story.caption.take(80)}"
                story.overlayText.isNotBlank() -> "Story: ${story.overlayText.take(80)}"
                story.mediaType == "video" -> "Story: 🎬 Video"
                else -> "Story: 📷 Photo"
            }

            sendMessage(
                userId = uid,
                contactId = targetContactId,
                sender = "me",
                text = cleanReply,
                aiGenerated = false,
                replyToText = storySnippet,
                replyToSender = story.authorName.ifBlank { story.authorUsername },
                replyToMessageId = story.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "stories/${story.id}/reply")
            Result.failure(e)
        }
    }

    override suspend fun deleteStory(userId: String, storyId: String): Result<Unit> {
        val path = "stories/$storyId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(storyId.isNotBlank()) { "storyId cannot be blank." }
            db.collection("stories").document(storyId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    override suspend fun purgeExpiredStories(userId: String) {
        val uid = auth.currentUser?.uid ?: return
        if (uid != userId) return
        runCatching {
            val nowMs = System.currentTimeMillis()
            val ownStories = db.collection("stories")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            val expiredDocs = ownStories.documents.filter { doc ->
                val exp = doc.getLong("expiresAtMs") ?: 0L
                exp in 1..nowMs
            }
            if (expiredDocs.isNotEmpty()) {
                val batch = db.batch()
                expiredDocs.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
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
                val existingDisplayName = existingUser.getString("displayName").orEmpty().ifBlank { "Nomi User" }
                val existingBio = existingUser.getString("bio").orEmpty().take(200)
                val existingProvider = existingUser.getString("provider").orEmpty().ifBlank { "google" }
                val existingPhotoData = existingUser.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
                val existingBannerData = existingUser.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
                val existingVisibility = ProfileVisibilityMode.fromKey(existingUser.getString("profileVisibility")).key
                @Suppress("UNCHECKED_CAST")
                val existingAllowedViewers = (existingUser.get("allowedViewerUids") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.take(500)
                    ?: emptyList()
                val existingMaxViewers = (existingUser.getLong("maxProfileViewers") ?: 0L).coerceIn(0L, 500L)
                if (existingHandle.isNotBlank()) {
                    val existingDirRef = db.collection("directory").document(existingHandle)
                    val existingDirSnap = existingDirRef.get().await()
                    if (existingDirSnap.exists() && existingDirSnap.getString("accountStatus") == "banned") {
                        return Result.success(UserSetupOutcome.Banned)
                    }
                    if (!existingDirSnap.exists()) {
                        val dirPayload = mutableMapOf<String, Any>(
                            "ownerUid" to uid,
                            "username" to existingHandle,
                            "displayName" to existingDisplayName.take(60),
                            "bio" to existingBio,
                            "provider" to existingProvider,
                            "profileVisibility" to existingVisibility,
                            "allowedViewerUids" to existingAllowedViewers,
                            "maxProfileViewers" to existingMaxViewers,
                            "online" to true,
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (existingPhotoData != null) {
                            dirPayload["photoData"] = existingPhotoData
                        }
                        if (existingBannerData != null) {
                            dirPayload["bannerData"] = existingBannerData
                        }
                        runCatching { existingDirRef.set(dirPayload).await() }
                    } else if (existingDirSnap.getString("ownerUid") == uid) {
                        val dirName = existingDirSnap.getString("displayName").orEmpty()
                        val dirBio = existingDirSnap.getString("bio").orEmpty()
                        val dirPhoto = existingDirSnap.getString("photoData")
                        val dirBanner = existingDirSnap.getString("bannerData")
                        val dirVisibility = existingDirSnap.getString("profileVisibility")
                        val dirSync = mutableMapOf<String, Any>()
                        if (existingDisplayName.isNotBlank() && dirName != existingDisplayName.take(60)) {
                            dirSync["displayName"] = existingDisplayName.take(60)
                        }
                        if (dirBio != existingBio) {
                            dirSync["bio"] = existingBio
                        }
                        if (existingPhotoData != null && dirPhoto != existingPhotoData) {
                            dirSync["photoData"] = existingPhotoData
                        }
                        if (existingBannerData != null && dirBanner != existingBannerData) {
                            dirSync["bannerData"] = existingBannerData
                        }
                        if (dirVisibility != existingVisibility) {
                            dirSync["profileVisibility"] = existingVisibility
                            dirSync["allowedViewerUids"] = existingAllowedViewers
                            dirSync["maxProfileViewers"] = existingMaxViewers
                        }
                        if (dirSync.isNotEmpty()) {
                            dirSync["updatedAt"] = FieldValue.serverTimestamp()
                            runCatching { existingDirRef.update(dirSync).await() }
                        }
                    }
                    if (existingHandle.isNotBlank()) {
                        val existingUsernameRef = db.collection("usernames").document(existingHandle)
                        val existingUsernameSnap = runCatching { existingUsernameRef.get().await() }.getOrNull()
                        if (existingUsernameSnap != null && !existingUsernameSnap.exists()) {
                            runCatching {
                                existingUsernameRef.set(
                                    mapOf(
                                        "username" to existingHandle,
                                        "ownerUid" to uid,
                                        "status" to "active",
                                        "createdAt" to FieldValue.serverTimestamp(),
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                ).await()
                            }
                        }
                    }
                }

                when (authFlowMode) {
                    AuthFlowMode.GOOGLE_QUICK -> {
                        return Result.success(UserSetupOutcome.Allowed)
                    }
                    AuthFlowMode.RETURNING_LOGIN -> {
                        if (pendingPassword.isEmpty() && returningIdentifier.isBlank()) {
                            return Result.success(UserSetupOutcome.Allowed)
                        }
                        if (pendingPassword.isEmpty()) {
                            // Session persisted across restart/force-close after previous login
                            return Result.success(UserSetupOutcome.Allowed)
                        }
                        val cleanUser = normalizeUsername(returningIdentifier)
                        if (cleanUser.isEmpty()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Enter both username and password.")
                            )
                        }
                        if (cleanUser.length < 2) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Account Not Found")
                            )
                        }

                        val usernameSnap = db.collection("usernames").document(cleanUser).get().await()
                        if (usernameSnap.exists() && usernameSnap.getString("status") == "retired") {
                            return Result.success(
                                UserSetupOutcome.Rejected("Account Not Found")
                            )
                        }

                        val credSnap = userRef.collection("credentials").document(cleanUser).get().await()
                        if (!credSnap.exists()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Account Not Found")
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
                        if (pendingPassword.isEmpty()) {
                            // Session persisted across restart/force-close after previous signup
                            return Result.success(UserSetupOutcome.Allowed)
                        }
                        val cleanName = preferredDisplayName.trim().take(60)
                        val cleanUser = normalizeUsername(preferredUsername).take(40)
                        val cleanDob = preferredDob.trim().take(20)
                        if (cleanName.isBlank() || cleanUser.isBlank() || cleanDob.isBlank()) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Please fill in all fields.")
                            )
                        }
                        if (cleanUser.length < 2) {
                            return Result.success(
                                UserSetupOutcome.Rejected("Username must be at least 2 characters.")
                            )
                        }

                        val requestedUsernameRef = db.collection("usernames").document(cleanUser)
                        val requestedUsernameSnap = requestedUsernameRef.get().await()
                        val requestedDirRef = db.collection("directory").document(cleanUser)
                        val requestedDirSnap = requestedDirRef.get().await()
                        val takenInUsernames = requestedUsernameSnap.exists() &&
                            (requestedUsernameSnap.getString("status") != "active" ||
                                requestedUsernameSnap.getString("ownerUid") != uid)
                        val takenInDir = requestedDirSnap.exists() &&
                            requestedDirSnap.getString("ownerUid") != uid
                        if (takenInUsernames || takenInDir) {
                            return Result.success(
                                UserSetupOutcome.Rejected("This username is already taken / already added.")
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
                        if (!requestedUsernameSnap.exists()) {
                            batch.set(
                                requestedUsernameRef,
                                mapOf(
                                    "username" to cleanUser,
                                    "ownerUid" to uid,
                                    "status" to "active",
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                        if (!requestedDirSnap.exists()) {
                            batch.set(
                                requestedDirRef,
                                mapOf(
                                    "ownerUid" to uid,
                                    "username" to cleanUser,
                                    "displayName" to cleanName,
                                    "provider" to (existingUser.getString("provider") ?: "username"),
                                    "online" to true,
                                    "showOnlineStatus" to true,
                                    "accountStatus" to "active",
                                    "reportCount" to 0L,
                                    "reportedByUids" to emptyList<String>(),
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                        if (existingHandle.isNotBlank() && existingHandle != cleanUser) {
                            val oldUsernameRef = db.collection("usernames").document(existingHandle)
                            val oldUsernameSnap = runCatching { oldUsernameRef.get().await() }.getOrNull()
                            if (oldUsernameSnap != null && oldUsernameSnap.exists() &&
                                oldUsernameSnap.getString("status") == "active" &&
                                oldUsernameSnap.getString("ownerUid") == uid
                            ) {
                                batch.update(
                                    oldUsernameRef,
                                    mapOf(
                                        "status" to "retired",
                                        "ownerUid" to "",
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                )
                            }
                            val oldDirRef = db.collection("directory").document(existingHandle)
                            val oldDirSnap = runCatching { oldDirRef.get().await() }.getOrNull()
                            if (oldDirSnap != null && oldDirSnap.exists() && oldDirSnap.getString("ownerUid") == uid) {
                                batch.delete(oldDirRef)
                            }
                            val oldCredRef = userRef.collection("credentials").document(existingHandle)
                            val oldCredSnap = runCatching { oldCredRef.get().await() }.getOrNull()
                            if (oldCredSnap != null && oldCredSnap.exists()) {
                                batch.delete(oldCredRef)
                            }
                        }
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
                        UserSetupOutcome.Rejected("Account Not Found")
                    )
                }
                val usernameSnap = db.collection("usernames").document(cleanUser).get().await()
                if (usernameSnap.exists() && usernameSnap.getString("status") == "retired") {
                    return Result.success(
                        UserSetupOutcome.Rejected("Account Not Found")
                    )
                }
                val credSnap = userRef.collection("credentials").document(cleanUser).get().await()
                if (!credSnap.exists()) {
                    return Result.success(
                        UserSetupOutcome.Rejected("Account Not Found")
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
                createFreshUserProfile(
                    uid = uid,
                    userRef = userRef,
                    resolvedName = restoredName,
                    resolvedUsername = cleanUser,
                    resolvedEmail = googleEmail?.trim().orEmpty(),
                    resolvedDob = restoredDob,
                    resolvedProvider = "username",
                    dirRef = null,
                    usernameRef = if (usernameSnap.exists()) null else db.collection("usernames").document(cleanUser)
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

            val usernameRefCandidate = db.collection("usernames").document(baseHandle)
            val usernameSnapCandidate = usernameRefCandidate.get().await()
            val dirRefCandidate = db.collection("directory").document(baseHandle)
            val dirSnapCandidate = dirRefCandidate.get().await()

            val resolvedUsername: String
            val dirRefToCreate: com.google.firebase.firestore.DocumentReference?
            val usernameRefToCreate: com.google.firebase.firestore.DocumentReference?

            val isUsernameUnavailable = (usernameSnapCandidate.exists() &&
                (usernameSnapCandidate.getString("status") != "active" ||
                    usernameSnapCandidate.getString("ownerUid") != uid)) ||
                dirSnapCandidate.exists()

            if (isUsernameUnavailable) {
                if (dirSnapCandidate.exists() &&
                    dirSnapCandidate.getString("accountStatus") == "banned" &&
                    dirSnapCandidate.getString("ownerUid") == uid
                ) {
                    return Result.success(UserSetupOutcome.Banned)
                }
                if (authFlowMode == AuthFlowMode.SIGN_UP) {
                    return Result.success(
                        UserSetupOutcome.Rejected("This username is already taken / already added.")
                    )
                } else if (dirSnapCandidate.exists() && dirSnapCandidate.getString("ownerUid") == uid &&
                    (!usernameSnapCandidate.exists() ||
                        (usernameSnapCandidate.getString("status") == "active" &&
                            usernameSnapCandidate.getString("ownerUid") == uid))
                ) {
                    resolvedUsername = baseHandle
                    dirRefToCreate = null
                    usernameRefToCreate = if (usernameSnapCandidate.exists()) null else usernameRefCandidate
                } else {
                    val fallbackHandle = "${baseHandle.take(30)}_${uid.take(6).lowercase()}"
                    val fallbackUsernameRef = db.collection("usernames").document(fallbackHandle)
                    val fallbackUsernameSnap = fallbackUsernameRef.get().await()
                    val fallbackRef = db.collection("directory").document(fallbackHandle)
                    val fallbackSnap = fallbackRef.get().await()
                    val fallbackBusy = (fallbackUsernameSnap.exists() &&
                        (fallbackUsernameSnap.getString("status") != "active" ||
                            fallbackUsernameSnap.getString("ownerUid") != uid)) ||
                        (fallbackSnap.exists() && fallbackSnap.getString("ownerUid") != uid)
                    if (fallbackBusy) {
                        val uniqueHandle = "${baseHandle.take(24)}_${(System.currentTimeMillis() % 100000)}"
                        resolvedUsername = uniqueHandle
                        dirRefToCreate = db.collection("directory").document(uniqueHandle)
                        usernameRefToCreate = db.collection("usernames").document(uniqueHandle)
                    } else {
                        resolvedUsername = fallbackHandle
                        dirRefToCreate = if (fallbackSnap.exists()) null else fallbackRef
                        usernameRefToCreate = if (fallbackUsernameSnap.exists()) null else fallbackUsernameRef
                    }
                }
            } else {
                resolvedUsername = baseHandle
                dirRefToCreate = dirRefCandidate
                usernameRefToCreate = if (usernameSnapCandidate.exists()) null else usernameRefCandidate
            }

            val resolvedDob = preferredDob.trim().ifBlank { "2000-01-01" }.take(20)
            val resolvedProvider = if (authFlowMode == AuthFlowMode.SIGN_UP) "username" else "google"
            val credPair = if (authFlowMode == AuthFlowMode.SIGN_UP && pendingPassword.isNotEmpty()) {
                val salt = PasswordHasher.generateSalt()
                val hash = PasswordHasher.hashPassword(pendingPassword, salt)
                salt to hash
            } else null

            createFreshUserProfile(
                uid = uid,
                userRef = userRef,
                resolvedName = resolvedName,
                resolvedUsername = resolvedUsername,
                resolvedEmail = googleEmail?.trim().orEmpty(),
                resolvedDob = resolvedDob,
                resolvedProvider = resolvedProvider,
                dirRef = dirRefToCreate,
                usernameRef = usernameRefToCreate,
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

    private suspend fun createFreshUserProfile(
        uid: String,
        userRef: com.google.firebase.firestore.DocumentReference,
        resolvedName: String,
        resolvedUsername: String,
        resolvedEmail: String,
        resolvedDob: String,
        resolvedProvider: String,
        dirRef: com.google.firebase.firestore.DocumentReference?,
        usernameRef: com.google.firebase.firestore.DocumentReference? = null,
        credentialSaltAndHash: Pair<String, String>? = null
    ) {
        val batch = db.batch()
        val defaultBio = "Dream Big... 💚"
        val profilePayload = mapOf(
            "userId" to uid,
            "displayName" to resolvedName,
            "username" to resolvedUsername,
            "email" to resolvedEmail,
            "bio" to defaultBio,
            "dob" to resolvedDob,
            "provider" to resolvedProvider,
            "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
            "allowedViewerUids" to emptyList<String>(),
            "maxProfileViewers" to 0L,
            "online" to true,
            "showOnlineStatus" to true,
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

        if (usernameRef != null) {
            batch.set(
                usernameRef,
                mapOf(
                    "username" to resolvedUsername,
                    "ownerUid" to uid,
                    "status" to "active",
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
                    "bio" to defaultBio,
                    "provider" to resolvedProvider,
                    "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                    "allowedViewerUids" to emptyList<String>(),
                    "maxProfileViewers" to 0L,
                    "online" to true,
                    "showOnlineStatus" to true,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "reportedByUids" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
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
        photoData: String?,
        bannerData: String?
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
            var requestedHandle: String? = null
            username?.let { rawHandle ->
                val clean = normalizeUsername(rawHandle)
                require(clean.length in 2..40) { "Username must be between 2 and 40 characters." }
                requestedHandle = clean
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
            bannerData?.let {
                require(it.length <= 150000) { "Profile banner is too large." }
                updates["bannerData"] = it
            }

            val userRef = db.collection("users").document(uid)
            val currentUserSnap = userRef.get().await()
            val currentHandle = currentUserSnap.getString("username").orEmpty()
            val previousHandle = currentHandle.takeIf { it.isNotBlank() }?.let { sanitizeHandle(it) }.orEmpty()
            val targetNewHandle = requestedHandle

            if (targetNewHandle != null && targetNewHandle != previousHandle) {
                val existingReservation = db.collection("usernames").document(targetNewHandle).get().await()
                if (existingReservation.exists() &&
                    (existingReservation.getString("status") != "active" ||
                        existingReservation.getString("ownerUid") != uid)
                ) {
                    return Result.failure(
                        IllegalStateException("This username is already taken / already added.")
                    )
                }
                val existingDir = db.collection("directory").document(targetNewHandle).get().await()
                if (existingDir.exists() && existingDir.getString("ownerUid") != uid) {
                    return Result.failure(
                        IllegalStateException("This username is already taken / already added.")
                    )
                }
            }

            val currentDisplayName = currentUserSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }
            val currentBio = currentUserSnap.getString("bio").orEmpty().take(200)
            val currentDob = currentUserSnap.getString("dob").orEmpty().ifBlank { "2000-01-01" }.take(20)
            val currentProvider = currentUserSnap.getString("provider").orEmpty().ifBlank { "google" }
            val currentPhotoData = currentUserSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
            val currentBannerData = currentUserSnap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
            val currentVisibility = ProfileVisibilityMode.fromKey(currentUserSnap.getString("profileVisibility")).key
            val currentShowOnlineStatus = currentUserSnap.getBoolean("showOnlineStatus") ?: true
            val currentOnline = (currentUserSnap.getBoolean("online") ?: true) && currentShowOnlineStatus
            @Suppress("UNCHECKED_CAST")
            val currentAllowedViewers = (currentUserSnap.get("allowedViewerUids") as? List<*>)
                ?.mapNotNull { it as? String }
                ?.take(500)
                ?: emptyList()
            val currentMaxViewers = (currentUserSnap.getLong("maxProfileViewers") ?: 0L).coerceIn(0L, 500L)

            val updatedHandleRaw = (updates["username"] as? String) ?: currentHandle
            val updatedHandle = updatedHandleRaw.takeIf { it.isNotBlank() }?.let { sanitizeHandle(it) }.orEmpty()
            val updatedName = (updates["displayName"] as? String) ?: currentDisplayName
            val updatedBio = (updates["bio"] as? String) ?: currentBio
            val updatedDob = (updates["dob"] as? String) ?: currentDob
            val updatedPhotoData = (updates["photoData"] as? String) ?: currentPhotoData
            val updatedBannerData = (updates["bannerData"] as? String) ?: currentBannerData

            if (targetNewHandle != null && targetNewHandle != previousHandle) {
                val newUsernameRef = db.collection("usernames").document(targetNewHandle)
                val newDirRef = db.collection("directory").document(targetNewHandle)
                val newCredRef = userRef.collection("credentials").document(targetNewHandle)
                val oldUsernameRef = previousHandle.takeIf { it.isNotBlank() }?.let { db.collection("usernames").document(it) }
                val oldDirRef = previousHandle.takeIf { it.isNotBlank() }?.let { db.collection("directory").document(it) }
                val oldCredRef = previousHandle.takeIf { it.isNotBlank() }?.let { userRef.collection("credentials").document(it) }

                db.runTransaction { tx ->
                    // All transaction reads must happen before any writes
                    val newUsernameSnap = tx.get(newUsernameRef)
                    val newDirSnap = tx.get(newDirRef)
                    val oldUsernameSnap = oldUsernameRef?.let { tx.get(it) }
                    val oldDirSnap = oldDirRef?.let { tx.get(it) }
                    val oldCredSnap = oldCredRef?.let { tx.get(it) }

                    if (newUsernameSnap.exists() &&
                        (newUsernameSnap.getString("status") != "active" ||
                            newUsernameSnap.getString("ownerUid") != uid)
                    ) {
                        throw IllegalStateException("This username is already taken / already added.")
                    }
                    if (newDirSnap.exists() && newDirSnap.getString("ownerUid") != uid) {
                        throw IllegalStateException("This username is already taken / already added.")
                    }

                    tx.update(userRef, updates)

                    if (!newUsernameSnap.exists()) {
                        tx.set(
                            newUsernameRef,
                            mapOf(
                                "username" to targetNewHandle,
                                "ownerUid" to uid,
                                "status" to "active",
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }

                    if (!newDirSnap.exists()) {
                        val dirPayload = mutableMapOf<String, Any>(
                            "ownerUid" to uid,
                            "username" to targetNewHandle,
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "provider" to currentProvider,
                            "profileVisibility" to currentVisibility,
                            "allowedViewerUids" to currentAllowedViewers,
                            "maxProfileViewers" to currentMaxViewers,
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) {
                            dirPayload["photoData"] = updatedPhotoData
                        }
                        if (!updatedBannerData.isNullOrBlank()) {
                            dirPayload["bannerData"] = updatedBannerData
                        }
                        tx.set(newDirRef, dirPayload)
                    } else {
                        val dirUpdates = mutableMapOf<String, Any>(
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) {
                            dirUpdates["photoData"] = updatedPhotoData
                        }
                        if (!updatedBannerData.isNullOrBlank()) {
                            dirUpdates["bannerData"] = updatedBannerData
                        }
                        tx.update(newDirRef, dirUpdates)
                    }

                    // Lock old username permanently as retired so it cannot be reused, searched, or logged into again
                    if (oldUsernameRef != null && oldUsernameSnap != null && oldUsernameSnap.exists() &&
                        oldUsernameSnap.getString("status") == "active" &&
                        oldUsernameSnap.getString("ownerUid") == uid
                    ) {
                        tx.update(
                            oldUsernameRef,
                            mapOf(
                                "status" to "retired",
                                "ownerUid" to "",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    if (oldDirRef != null && oldDirSnap != null && oldDirSnap.exists() &&
                        oldDirSnap.getString("ownerUid") == uid
                    ) {
                        tx.delete(oldDirRef)
                    }
                    if (oldCredRef != null && oldCredSnap != null && oldCredSnap.exists()) {
                        val salt = oldCredSnap.getString("passwordSalt").orEmpty()
                        val hash = oldCredSnap.getString("passwordHash").orEmpty()
                        val status = oldCredSnap.getString("accountStatus").orEmpty().ifBlank { "active" }
                        if (salt.length >= 16 && hash.length == 64) {
                            tx.set(
                                newCredRef,
                                mapOf(
                                    "userId" to uid,
                                    "username" to targetNewHandle,
                                    "displayName" to updatedName.take(60),
                                    "dob" to updatedDob,
                                    "passwordSalt" to salt,
                                    "passwordHash" to hash,
                                    "accountStatus" to status,
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                            tx.delete(oldCredRef)
                        }
                    }
                    null
                }.await()
            } else {
                userRef.update(updates).await()

                if (updatedHandle.isNotBlank() && updatedName.isNotBlank()) {
                    val dirRef = db.collection("directory").document(updatedHandle)
                    val dirSnap = dirRef.get().await()
                    if (!dirSnap.exists()) {
                        val dirPayload = mutableMapOf<String, Any>(
                            "ownerUid" to uid,
                            "username" to updatedHandle,
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "provider" to currentProvider,
                            "profileVisibility" to currentVisibility,
                            "allowedViewerUids" to currentAllowedViewers,
                            "maxProfileViewers" to currentMaxViewers,
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) {
                            dirPayload["photoData"] = updatedPhotoData
                        }
                        if (!updatedBannerData.isNullOrBlank()) {
                            dirPayload["bannerData"] = updatedBannerData
                        }
                        dirRef.set(dirPayload).await()
                    } else if (dirSnap.getString("ownerUid") == uid) {
                        val dirUpdates = mutableMapOf<String, Any>(
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) {
                            dirUpdates["photoData"] = updatedPhotoData
                        }
                        if (!updatedBannerData.isNullOrBlank()) {
                            dirUpdates["bannerData"] = updatedBannerData
                        }
                        dirRef.update(dirUpdates).await()
                    }
                }

                if (previousHandle.isNotBlank() && (updates.containsKey("displayName") || updates.containsKey("dob"))) {
                    val oldCredRef = userRef.collection("credentials").document(previousHandle)
                    val oldCredSnap = runCatching { oldCredRef.get().await() }.getOrNull()
                    if (oldCredSnap != null && oldCredSnap.exists()) {
                        runCatching {
                            oldCredRef.update(
                                mapOf(
                                    "displayName" to updatedName.take(60),
                                    "dob" to updatedDob,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                        }
                    }
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

    override suspend fun updatePrivacySettings(
        userId: String,
        profileVisibility: String,
        allowedViewerUids: List<String>,
        maxProfileViewers: Long,
        showOnlineStatus: Boolean
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val cleanVisibility = ProfileVisibilityMode.fromKey(profileVisibility).key
            val cleanAllowed = allowedViewerUids
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 128 }
                .distinct()
                .take(500)
            val cleanMax = maxProfileViewers.coerceIn(0L, 500L)

            val userRef = db.collection("users").document(uid)
            val snap = userRef.get().await()
            val handle = sanitizeHandle(snap.getString("username").orEmpty())
            val displayName = snap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
            val bio = snap.getString("bio").orEmpty().take(200)
            val provider = snap.getString("provider").orEmpty().ifBlank { "google" }
            val photoData = snap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
            val bannerData = snap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 150000 }

            userRef.update(
                mapOf(
                    "profileVisibility" to cleanVisibility,
                    "allowedViewerUids" to cleanAllowed,
                    "maxProfileViewers" to cleanMax,
                    "showOnlineStatus" to showOnlineStatus,
                    "online" to showOnlineStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (handle.isNotBlank()) {
                val dirRef = db.collection("directory").document(handle)
                val dirSnap = dirRef.get().await()
                if (!dirSnap.exists()) {
                    val dirPayload = mutableMapOf<String, Any>(
                        "ownerUid" to uid,
                        "username" to handle,
                        "displayName" to displayName,
                        "bio" to bio,
                        "provider" to provider,
                        "profileVisibility" to cleanVisibility,
                        "allowedViewerUids" to cleanAllowed,
                        "maxProfileViewers" to cleanMax,
                        "online" to showOnlineStatus,
                        "showOnlineStatus" to showOnlineStatus,
                        "accountStatus" to "active",
                        "reportCount" to 0L,
                        "reportedByUids" to emptyList<String>(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (!photoData.isNullOrBlank()) dirPayload["photoData"] = photoData
                    if (!bannerData.isNullOrBlank()) dirPayload["bannerData"] = bannerData
                    dirRef.set(dirPayload).await()
                } else if (dirSnap.getString("ownerUid") == uid) {
                    dirRef.update(
                        mapOf(
                            "profileVisibility" to cleanVisibility,
                            "allowedViewerUids" to cleanAllowed,
                            "maxProfileViewers" to cleanMax,
                            "showOnlineStatus" to showOnlineStatus,
                            "online" to showOnlineStatus,
                            "bio" to bio,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun changePassword(
        userId: String,
        currentPassword: String,
        newPassword: String
    ): Result<Unit> {
        val docPath = "users/$userId/credentials"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(newPassword.length in 6..128) { "New password must be between 6 and 128 characters." }

            val userRef = db.collection("users").document(uid)
            val userSnap = userRef.get().await()
            val handle = sanitizeHandle(userSnap.getString("username").orEmpty())
            require(handle.length in 2..40) { "Set a valid username before updating your password." }
            val displayName = userSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
            val dob = userSnap.getString("dob").orEmpty().ifBlank { "2000-01-01" }.take(20)
            val accountStatus = userSnap.getString("accountStatus").orEmpty().ifBlank { "active" }

            val credRef = userRef.collection("credentials").document(handle)
            val credSnap = credRef.get().await()
            if (credSnap.exists()) {
                require(currentPassword.isNotEmpty()) { "Enter your current password." }
                val existingSalt = credSnap.getString("passwordSalt").orEmpty()
                val existingHash = credSnap.getString("passwordHash").orEmpty()
                if (!PasswordHasher.verifyPassword(currentPassword, existingSalt, existingHash)) {
                    return Result.failure(IllegalArgumentException("Current password is incorrect."))
                }
                val nextSalt = PasswordHasher.generateSalt()
                val nextHash = PasswordHasher.hashPassword(newPassword, nextSalt)
                credRef.update(
                    mapOf(
                        "passwordSalt" to nextSalt,
                        "passwordHash" to nextHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val nextSalt = PasswordHasher.generateSalt()
                val nextHash = PasswordHasher.hashPassword(newPassword, nextSalt)
                credRef.set(
                    mapOf(
                        "userId" to uid,
                        "username" to handle,
                        "displayName" to displayName,
                        "dob" to dob,
                        "passwordSalt" to nextSalt,
                        "passwordHash" to nextHash,
                        "accountStatus" to accountStatus,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun refreshUserData(userId: String): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            runCatching { db.enableNetwork().await() }
            val userSnap = runCatching {
                db.collection("users").document(uid).get(Source.SERVER).await()
            }.getOrElse {
                db.collection("users").document(uid).get().await()
            }
            val handle = sanitizeHandle(userSnap.getString("username").orEmpty())
            if (handle.isNotBlank()) {
                runCatching { db.collection("directory").document(handle).get(Source.SERVER).await() }
            }
            runCatching {
                db.collection("users").document(uid).collection("contacts").get(Source.SERVER).await()
            }
            runCatching {
                db.collection("stories").limit(60).get(Source.SERVER).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docPath)
            Result.failure(e)
        }
    }

    override suspend fun lookupUserByUsername(
        username: String,
        viewerUserId: String
    ): Result<DirectoryLookupResult?> {
        val cleanHandle = exactUsernameOrNull(username) ?: return Result.success(null)
        val path = "directory/$cleanHandle"
        return try {
            val viewerUid = viewerUserId.ifBlank { requireUserId() }
            val usernameSnap = db.collection("usernames").document(cleanHandle).get(Source.SERVER).await()
            if (usernameSnap.exists() && usernameSnap.getString("status") != "active") {
                return Result.success(null)
            }
            val snap = db.collection("directory").document(cleanHandle).get(Source.SERVER).await()
            if (!snap.exists()) return Result.success(null)
            if (snap.getString("accountStatus") == "banned") {
                return Result.failure(IllegalStateException("This account is banned, so it cannot be added here."))
            }
            val ownerUid = snap.getString("ownerUid").orEmpty()
            if (ownerUid.isBlank()) return Result.success(null)
            if (usernameSnap.exists() &&
                (usernameSnap.getString("ownerUid").orEmpty() != ownerUid ||
                    !usernameSnap.getString("username").orEmpty().equals(cleanHandle, ignoreCase = true))
            ) {
                return Result.success(null)
            }
            val storedHandle = snap.getString("username").orEmpty().ifBlank { cleanHandle }
            if (!storedHandle.equals(cleanHandle, ignoreCase = true)) return Result.success(null)
            val displayName = snap.getString("displayName").orEmpty().ifBlank { storedHandle }
            val visibility = snap.getString("profileVisibility")
            @Suppress("UNCHECKED_CAST")
            val allowedViewers = (snap.get("allowedViewerUids") as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()
            val maxViewers = snap.getLong("maxProfileViewers") ?: 0L
            val visible = canViewerSeeProfile(
                profileVisibility = visibility,
                allowedViewerUids = allowedViewers,
                maxProfileViewers = maxViewers,
                ownerUid = ownerUid,
                viewerUid = viewerUid
            )
            val bio = if (visible) snap.getString("bio").orEmpty() else ""
            val photoData = if (visible) snap.getString("photoData")?.takeIf { it.isNotBlank() } else null
            val bannerData = if (visible) snap.getString("bannerData")?.takeIf { it.isNotBlank() } else null
            Result.success(
                DirectoryLookupResult(
                    username = storedHandle,
                    displayName = displayName,
                    ownerUid = ownerUid,
                    bio = bio,
                    photoData = photoData,
                    bannerData = bannerData
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

            val validType = if (threadType == "group") "group" else "chat"
            val rawHandle = username.trim().removePrefix("@")
            val cleanHandle: String
            var resolvedPeerUid = peerUid
            var resolvedIsPeer = isRegisteredPeer
            var resolvedDirName = ""
            var resolvedBio = ""
            var resolvedOnline = false
            var resolvedPhotoData: String? = null
            var resolvedBannerData: String? = null
            val finalName: String

            if (validType == "chat") {
                cleanHandle = exactUsernameOrNull(username)
                    ?: return Result.failure(IllegalStateException("Account Not Found"))
                val usernameSnap = db.collection("usernames").document(cleanHandle).get(Source.SERVER).await()
                if (usernameSnap.exists() && usernameSnap.getString("status") != "active") {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                val dirSnap = db.collection("directory").document(cleanHandle).get(Source.SERVER).await()
                if (!dirSnap.exists()) {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                if (dirSnap.getString("accountStatus") == "banned") {
                    return Result.failure(IllegalStateException("This account is banned, so it cannot be added here."))
                }
                val owner = dirSnap.getString("ownerUid").orEmpty()
                val storedHandle = dirSnap.getString("username").orEmpty()
                if (owner.isBlank() || !storedHandle.equals(cleanHandle, ignoreCase = true)) {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                if (usernameSnap.exists() &&
                    (usernameSnap.getString("ownerUid").orEmpty() != owner ||
                        !usernameSnap.getString("username").orEmpty().equals(cleanHandle, ignoreCase = true))
                ) {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                if (owner == uid) {
                    return Result.failure(IllegalStateException("You cannot add your own username."))
                }
                resolvedPeerUid = owner
                resolvedIsPeer = true
                resolvedDirName = dirSnap.getString("displayName").orEmpty()
                @Suppress("UNCHECKED_CAST")
                val allowedViewers = (dirSnap.get("allowedViewerUids") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?: emptyList()
                val visible = canViewerSeeProfile(
                    profileVisibility = dirSnap.getString("profileVisibility"),
                    allowedViewerUids = allowedViewers,
                    maxProfileViewers = dirSnap.getLong("maxProfileViewers") ?: 0L,
                    ownerUid = owner,
                    viewerUid = uid
                )
                resolvedBio = if (visible) dirSnap.getString("bio").orEmpty().take(200) else ""
                resolvedPhotoData = if (visible) {
                    dirSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
                } else null
                resolvedBannerData = if (visible) {
                    dirSnap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 150000 }
                } else null
                val updatedTs = dirSnap.getTimestamp("updatedAt")?.toDate()?.time
                val fresh = updatedTs == null || (System.currentTimeMillis() - updatedTs) <= PRESENCE_STALE_MS
                val showOnline = dirSnap.getBoolean("showOnlineStatus") ?: true
                resolvedOnline = showOnline && (dirSnap.getBoolean("online") == true) && fresh
                finalName = resolvedDirName.trim().ifBlank { storedHandle }.take(60)
            } else {
                cleanHandle = sanitizeHandle(rawHandle.ifBlank { name })
                val cleanGroupName = name.trim().ifBlank { cleanHandle }.take(60)
                require(cleanGroupName.isNotEmpty()) { "Group name cannot be empty." }
                finalName = cleanGroupName
            }
            val cleanPhone = phone.trim().ifBlank { "@$cleanHandle" }.take(30)

            if (validType == "chat" && cleanHandle.isNotBlank()) {
                val contactsRef = db.collection("users").document(uid).collection("contacts")
                val existingByUsername = contactsRef
                    .whereEqualTo("username", cleanHandle)
                    .limit(1)
                    .get()
                    .await()
                    .documents
                    .firstOrNull()
                val existingDoc = existingByUsername ?: if (resolvedPeerUid.isNotBlank()) {
                    contactsRef
                        .whereEqualTo("peerUid", resolvedPeerUid)
                        .limit(1)
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                } else null
                if (existingDoc != null) {
                    val existingUpdates = mutableMapOf<String, Any>(
                        "username" to cleanHandle,
                        "phone" to cleanPhone,
                        "peerUid" to resolvedPeerUid,
                        "isRegisteredPeer" to resolvedIsPeer,
                        "bio" to resolvedBio,
                        "online" to resolvedOnline,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (existingDoc.getBoolean("customRenamed") != true) {
                        existingUpdates["name"] = finalName
                    }
                    if (!resolvedPhotoData.isNullOrBlank()) {
                        existingUpdates["photoData"] = resolvedPhotoData
                    } else {
                        existingUpdates["photoData"] = FieldValue.delete()
                    }
                    if (!resolvedBannerData.isNullOrBlank()) {
                        existingUpdates["bannerData"] = resolvedBannerData
                    } else {
                        existingUpdates["bannerData"] = FieldValue.delete()
                    }
                    existingDoc.reference.update(existingUpdates).await()
                    return Result.success(existingDoc.id)
                }
            }

            val contactRef = db.collection("users").document(uid).collection("contacts").document()
            path = contactRef.path
            val contactPayload = mutableMapOf<String, Any>(
                "userId" to uid,
                "name" to finalName,
                "username" to cleanHandle,
                "phone" to cleanPhone,
                "threadType" to validType,
                "bio" to resolvedBio,
                "online" to resolvedOnline,
                "typing" to false,
                "unreadCount" to 0L,
                "lastMessageText" to "",
                "lastMessageFromMe" to false,
                "lastMessageTimeLabel" to "",
                "pinned" to false,
                "locked" to false,
                "blocked" to false,
                "archived" to false,
                "customRenamed" to false,
                "peerUid" to resolvedPeerUid,
                "isRegisteredPeer" to resolvedIsPeer,
                "accountStatus" to "active",
                "reportCount" to 0L,
                "reportedBy" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!resolvedPhotoData.isNullOrBlank()) {
                contactPayload["photoData"] = resolvedPhotoData
            }
            if (!resolvedBannerData.isNullOrBlank()) {
                contactPayload["bannerData"] = resolvedBannerData
            }
            contactRef.set(contactPayload).await()
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
        incrementUnreadIfThem: Boolean,
        messageType: String,
        mediaData: String?,
        durationSec: Long,
        replyToText: String?,
        replyToSender: String?,
        replyToMessageId: String?
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId/messages"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
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

            val userRef = db.collection("users").document(uid)
            val contactRef = userRef.collection("contacts").document(contactId)
            val msgRef = contactRef.collection("messages").document()

            val contactSnap = contactRef.get().await()
            var peerUid = contactSnap.getString("peerUid").orEmpty()
            val peerUsername = contactSnap.getString("username").orEmpty()
            val threadType = contactSnap.getString("threadType").orEmpty()
            val disappearingSec = (contactSnap.getLong("disappearingSeconds") ?: 0L).coerceAtLeast(0L)
            val expiresAtMs = if (disappearingSec > 0L) {
                System.currentTimeMillis() + disappearingSec * 1000L
            } else {
                0L
            }
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

            val msgPayload = mutableMapOf<String, Any>(
                "userId" to uid,
                "contactId" to contactId,
                "sender" to validSender,
                "text" to cleanText,
                "messageType" to cleanType,
                "durationSec" to cleanDuration,
                "expiresAtMs" to expiresAtMs,
                "timeLabel" to timeLabel,
                "aiGenerated" to aiGenerated,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (cleanMedia != null) {
                msgPayload["mediaData"] = cleanMedia
            }
            if (!replyToText.isNullOrBlank()) {
                msgPayload["replyToText"] = replyToText.trim().take(200)
            }
            if (!replyToSender.isNullOrBlank()) {
                msgPayload["replyToSender"] = replyToSender.trim().take(60)
            }
            if (!replyToMessageId.isNullOrBlank()) {
                msgPayload["replyToMessageId"] = replyToMessageId.trim().take(128)
            }

            val batch = db.batch()
            batch.set(msgRef, msgPayload)
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
                val relayPayload = mutableMapOf<String, Any>(
                    "fromUid" to uid,
                    "toUid" to peerUid,
                    "fromUsername" to myUsername,
                    "fromDisplayName" to myDisplayName,
                    "toUsername" to peerUsername.ifBlank { "nomiuser" }.take(40),
                    "text" to cleanText,
                    "messageType" to cleanType,
                    "durationSec" to cleanDuration,
                    "timeLabel" to timeLabel,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                if (cleanMedia != null) {
                    relayPayload["mediaData"] = cleanMedia
                }
                if (!replyToText.isNullOrBlank()) {
                    relayPayload["replyToText"] = replyToText.trim().take(200)
                }
                if (!replyToSender.isNullOrBlank()) {
                    relayPayload["replyToSender"] = replyToSender.trim().take(60)
                }
                if (!replyToMessageId.isNullOrBlank()) {
                    relayPayload["replyToMessageId"] = replyToMessageId.trim().take(128)
                }
                batch.set(relayRef, relayPayload)
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
                    val messageType = doc.getString("messageType")?.takeIf {
                        it in listOf("text", "voice", "image", "file", "call")
                    } ?: "text"
                    val mediaData = doc.getString("mediaData")?.takeIf { it.isNotBlank() && it.length <= 240_000 }
                    val durationSec = (doc.getLong("durationSec") ?: 0L).coerceIn(0L, 600L)
                    val timeLabel = doc.getString("timeLabel").orEmpty().ifBlank { currentFormattedTime() }.take(30)
                    val replyToText = doc.getString("replyToText")?.takeIf { it.isNotBlank() }?.take(200)
                    val replyToSender = doc.getString("replyToSender")?.takeIf { it.isNotBlank() }?.take(60)
                    val replyToMessageId = doc.getString("replyToMessageId")?.takeIf { it.isNotBlank() }?.take(128)
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

                        val peerDirSnap = runCatching {
                            db.collection("directory").document(fromUsername).get().await()
                        }.getOrNull()
                        val livePeerName = peerDirSnap?.getString("displayName")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(60)
                            ?: fromDisplayName
                        @Suppress("UNCHECKED_CAST")
                        val peerAllowedViewers = (peerDirSnap?.get("allowedViewerUids") as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?: emptyList()
                        val visibleToMe = if (peerDirSnap == null || !peerDirSnap.exists()) {
                            true
                        } else {
                            canViewerSeeProfile(
                                profileVisibility = peerDirSnap.getString("profileVisibility"),
                                allowedViewerUids = peerAllowedViewers,
                                maxProfileViewers = peerDirSnap.getLong("maxProfileViewers") ?: 0L,
                                ownerUid = peerDirSnap.getString("ownerUid").orEmpty().ifBlank { fromUid },
                                viewerUid = uid
                            )
                        }
                        val peerBio = if (visibleToMe) {
                            peerDirSnap?.getString("bio").orEmpty().take(200)
                        } else ""
                        val peerPhotoData = if (visibleToMe) {
                            peerDirSnap?.getString("photoData")
                                ?.takeIf { it.isNotBlank() && it.length <= 150000 }
                        } else null
                        val peerBannerData = if (visibleToMe) {
                            peerDirSnap?.getString("bannerData")
                                ?.takeIf { it.isNotBlank() && it.length <= 150000 }
                        } else null

                        val batch = db.batch()
                        val targetContactId: String
                        var isBlockedContact = false
                        var isMutedContact = false
                        if (existingDoc == null) {
                            val newContactRef = userRef.collection("contacts").document()
                            targetContactId = newContactRef.id
                            val msgRef = newContactRef.collection("messages").document()
                            val isActiveOpen = activeContactIdProvider() == targetContactId
                            val newContactPayload = mutableMapOf<String, Any>(
                                "userId" to uid,
                                "name" to livePeerName,
                                "username" to fromUsername,
                                "phone" to "@$fromUsername",
                                "threadType" to "chat",
                                "bio" to peerBio,
                                "online" to false,
                                "typing" to false,
                                "unreadCount" to if (isActiveOpen) 0L else 1L,
                                "lastMessageText" to text.take(1000),
                                "lastMessageFromMe" to false,
                                "lastMessageTimeLabel" to timeLabel,
                                "pinned" to false,
                                "locked" to false,
                                "blocked" to false,
                                "archived" to false,
                                "muted" to false,
                                "disappearingSeconds" to 0L,
                                "chatTheme" to "classic",
                                "customRenamed" to false,
                                "peerUid" to fromUid,
                                "isRegisteredPeer" to true,
                                "accountStatus" to "active",
                                "reportCount" to 0L,
                                "reportedBy" to emptyList<String>(),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            if (!peerPhotoData.isNullOrBlank()) {
                                newContactPayload["photoData"] = peerPhotoData
                            }
                            if (!peerBannerData.isNullOrBlank()) {
                                newContactPayload["bannerData"] = peerBannerData
                            }
                            batch.set(newContactRef, newContactPayload)
                            val msgPayload = mutableMapOf<String, Any>(
                                "userId" to uid,
                                "contactId" to targetContactId,
                                "sender" to "them",
                                "text" to text,
                                "messageType" to messageType,
                                "durationSec" to durationSec,
                                "expiresAtMs" to 0L,
                                "timeLabel" to timeLabel,
                                "aiGenerated" to false,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                            if (mediaData != null) {
                                msgPayload["mediaData"] = mediaData
                            }
                            if (replyToText != null) {
                                msgPayload["replyToText"] = replyToText
                            }
                            if (replyToSender != null) {
                                msgPayload["replyToSender"] = replyToSender
                            }
                            if (replyToMessageId != null) {
                                msgPayload["replyToMessageId"] = replyToMessageId
                            }
                            batch.set(msgRef, msgPayload)
                        } else {
                            targetContactId = existingDoc.id
                            isBlockedContact = existingDoc.getBoolean("blocked") == true
                            isMutedContact = existingDoc.getBoolean("muted") == true
                            val isCustomRenamed = existingDoc.getBoolean("customRenamed") == true
                            val disappearingSec = (existingDoc.getLong("disappearingSeconds") ?: 0L).coerceAtLeast(0L)
                            val expiresAtMs = if (disappearingSec > 0L) {
                                System.currentTimeMillis() + disappearingSec * 1000L
                            } else {
                                0L
                            }
                            val contactRef = existingDoc.reference
                            val msgRef = contactRef.collection("messages").document()
                            val isActiveOpen = activeContactIdProvider() == targetContactId
                            val msgPayload = mutableMapOf<String, Any>(
                                "userId" to uid,
                                "contactId" to targetContactId,
                                "sender" to "them",
                                "text" to text,
                                "messageType" to messageType,
                                "durationSec" to durationSec,
                                "expiresAtMs" to expiresAtMs,
                                "timeLabel" to timeLabel,
                                "aiGenerated" to false,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                            if (mediaData != null) {
                                msgPayload["mediaData"] = mediaData
                            }
                            if (replyToText != null) {
                                msgPayload["replyToText"] = replyToText
                            }
                            if (replyToSender != null) {
                                msgPayload["replyToSender"] = replyToSender
                            }
                            if (replyToMessageId != null) {
                                msgPayload["replyToMessageId"] = replyToMessageId
                            }
                            batch.set(msgRef, msgPayload)
                            val existingContactUpdates = mutableMapOf<String, Any>(
                                "username" to fromUsername,
                                "peerUid" to fromUid,
                                "isRegisteredPeer" to true,
                                "bio" to peerBio,
                                "lastMessageText" to text.take(1000),
                                "lastMessageFromMe" to false,
                                "lastMessageTimeLabel" to timeLabel,
                                "unreadCount" to if (isActiveOpen) 0L else FieldValue.increment(1L),
                                "typing" to false,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            if (!isCustomRenamed) {
                                existingContactUpdates["name"] = livePeerName
                            }
                            if (!visibleToMe) {
                                existingContactUpdates["photoData"] = FieldValue.delete()
                                existingContactUpdates["bannerData"] = FieldValue.delete()
                            } else {
                                if (!peerPhotoData.isNullOrBlank()) {
                                    existingContactUpdates["photoData"] = peerPhotoData
                                }
                                if (!peerBannerData.isNullOrBlank()) {
                                    existingContactUpdates["bannerData"] = peerBannerData
                                }
                            }
                            batch.update(contactRef, existingContactUpdates)
                        }
                        batch.delete(doc.reference)
                        batch.commit().await()
                        if (!isBlockedContact && !isMutedContact) {
                            _incomingAlerts.tryEmit(
                                IncomingMessageAlert(
                                    contactId = targetContactId,
                                    senderName = fromDisplayName,
                                    senderHandle = fromUsername,
                                    text = text
                                )
                            )
                        }
                        true
                    }
                    if (delivered.isFailure) {
                        processedDocIds.remove(doc.id)
                    }
                }
            }
    }

    override suspend fun setUserPresence(userId: String, online: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        if (uid != userId) return
        runCatching {
            val userRef = db.collection("users").document(uid)
            val snap = userRef.get().await()
            if (!snap.exists()) return
            val showOnline = snap.getBoolean("showOnlineStatus") ?: true
            val effectiveOnline = online && showOnline
            userRef.update(
                mapOf(
                    "online" to effectiveOnline,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            val handle = sanitizeHandle(snap.getString("username").orEmpty())
            if (handle.isNotBlank()) {
                val dirRef = db.collection("directory").document(handle)
                val dirSnap = dirRef.get().await()
                if (dirSnap.exists() && dirSnap.getString("ownerUid") == uid) {
                    dirRef.update(
                        mapOf(
                            "online" to effectiveOnline,
                            "showOnlineStatus" to showOnline,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
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

    override suspend fun toggleArchiveChat(
        userId: String,
        contactId: String,
        archived: Boolean
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
                        "archived" to archived,
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

    override suspend fun toggleMuteChat(
        userId: String,
        contactId: String,
        muted: Boolean
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
                        "muted" to muted,
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

    override suspend fun setDisappearingMessages(
        userId: String,
        contactId: String,
        seconds: Long
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val cleanSeconds = seconds.coerceIn(0L, 7_776_000L)
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "disappearingSeconds" to cleanSeconds,
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

    override suspend fun setChatTheme(
        userId: String,
        contactId: String,
        themeKey: String
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val validKey = com.example.data.model.ChatThemeStyle.fromKey(themeKey).key
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "chatTheme" to validKey,
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

    override suspend fun clearChatMessages(
        userId: String,
        contactId: String
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId/messages"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val contactRef = db.collection("users").document(uid)
                .collection("contacts").document(contactId)
            val msgSnaps = contactRef.collection("messages").get().await()
            val batch = db.batch()
            msgSnaps.documents.forEach { batch.delete(it.reference) }
            batch.update(
                contactRef,
                mapOf(
                    "lastMessageText" to "",
                    "lastMessageFromMe" to false,
                    "lastMessageTimeLabel" to "",
                    "unreadCount" to 0L,
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

    override suspend fun renameChat(
        userId: String,
        contactId: String,
        newName: String
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val cleanName = newName.trim().take(60)
            require(cleanName.isNotEmpty()) { "Chat name cannot be empty." }
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .update(
                    mapOf(
                        "name" to cleanName,
                        "customRenamed" to true,
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

    override suspend fun deleteMessage(
        userId: String,
        contactId: String,
        messageId: String
    ): Result<Unit> {
        val path = "users/$userId/contacts/$contactId/messages/$messageId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank() && messageId.isNotBlank()) { "Invalid IDs." }
            db.collection("users").document(uid)
                .collection("contacts").document(contactId)
                .collection("messages").document(messageId)
                .delete()
                .await()
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
                "Thanks for reporting ${contact.name}. This account has been suspended."
            } else {
                "Thank you for reporting ${contact.name}. Our moderation team will review this account."
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

    private fun formatVoiceDuration(durationSec: Long): String {
        val sec = durationSec.coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L)
    }

    companion object {
        const val PRESENCE_STALE_MS = 150_000L

        fun normalizeUsername(raw: String): String {
            return raw.trim()
                .lowercase()
                .replace(Regex("\\s+"), "")
                .replace(Regex("[^a-z0-9_-]"), "_")
                .trim('_')
        }

        fun exactUsernameOrNull(raw: String): String? {
            val candidate = raw.trim().removePrefix("@").lowercase()
            return if (candidate.length in 2..40 && candidate.matches(Regex("^[a-z0-9_-]+$"))) {
                candidate
            } else {
                null
            }
        }

        fun canViewerSeeProfile(
            profileVisibility: String?,
            allowedViewerUids: List<String>,
            maxProfileViewers: Long,
            ownerUid: String?,
            viewerUid: String,
            viewerUsername: String = ""
        ): Boolean {
            val cleanOwner = ownerUid.orEmpty()
            if (cleanOwner.isNotBlank() && viewerUid.isNotBlank() && cleanOwner == viewerUid) return true
            if (viewerUsername.isNotBlank() && cleanOwner.equals(viewerUsername, ignoreCase = true)) return true
            return when (ProfileVisibilityMode.fromKey(profileVisibility)) {
                ProfileVisibilityMode.EVERYONE -> true
                ProfileVisibilityMode.NOBODY -> false
                ProfileVisibilityMode.SELECTED -> {
                    val cleanLimit = maxProfileViewers.coerceAtLeast(0L)
                    val effectiveAllowed = if (cleanLimit > 0L) {
                        allowedViewerUids.take(cleanLimit.toInt())
                    } else {
                        allowedViewerUids
                    }
                    (viewerUid.isNotBlank() && effectiveAllowed.contains(viewerUid)) ||
                        (viewerUsername.isNotBlank() && effectiveAllowed.any {
                            it.equals(viewerUsername, ignoreCase = true)
                        })
                }
            }
        }

        fun mergeThreadWithDirectory(
            thread: ChatThread,
            entry: UserDirectoryEntry?,
            nowMillis: Long = System.currentTimeMillis(),
            viewerUid: String = thread.userId,
            viewerUsername: String = ""
        ): ChatThread {
            if (entry == null) {
                return thread.copy(online = false)
            }
            if (thread.peerUid.isNotBlank() &&
                !entry.ownerUid.isNullOrBlank() &&
                entry.ownerUid != thread.peerUid
            ) {
                return thread.copy(online = false)
            }
            val syncedName = if (thread.customRenamed) {
                thread.name
            } else {
                entry.displayName.trim().ifBlank { thread.name }
            }
            val syncedStatus = if (entry.accountStatus == "banned") "banned" else thread.accountStatus
            val visibleToViewer = canViewerSeeProfile(
                profileVisibility = entry.profileVisibility,
                allowedViewerUids = entry.allowedViewerUids,
                maxProfileViewers = entry.maxProfileViewers,
                ownerUid = entry.ownerUid,
                viewerUid = viewerUid,
                viewerUsername = viewerUsername
            )
            val syncedPhoto = if (visibleToViewer) {
                entry.photoData?.takeIf { it.isNotBlank() } ?: thread.photoData
            } else null
            val syncedBanner = if (visibleToViewer) {
                entry.bannerData?.takeIf { it.isNotBlank() } ?: thread.bannerData
            } else null
            val syncedBio = if (visibleToViewer) {
                entry.bio.trim().ifBlank { thread.bio }
            } else ""
            val updatedMs = entry.updatedAt?.toDate()?.time
            val withinWindow = updatedMs == null || (nowMillis - updatedMs) <= PRESENCE_STALE_MS
            val realOnline = entry.showOnlineStatus && entry.online && syncedStatus != "banned" && withinWindow
            return thread.copy(
                name = syncedName,
                bio = syncedBio,
                photoData = syncedPhoto,
                bannerData = syncedBanner,
                online = realOnline,
                accountStatus = syncedStatus,
                peerUid = thread.peerUid.ifBlank { entry.ownerUid.orEmpty() },
                isRegisteredPeer = thread.isRegisteredPeer || !entry.ownerUid.isNullOrBlank()
            )
        }
    }
}

package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ADMIN_GMAIL
import com.example.data.model.AdminUserRecord
import com.example.data.model.AuthFlowMode
import com.example.data.model.BAN_REPORT_LIMIT
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.DirectoryLookupResult
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.MessageReceiptStatus
import com.example.data.model.ModerationAlert
import com.example.data.model.ProfileVisibilityMode
import com.example.data.model.ReportOutcome
import com.example.data.model.STORY_DURATION_MS
import com.example.data.model.StoryFontStyle
import com.example.data.model.StoryItem
import com.example.data.model.StoryViewRecord
import com.example.data.model.ThemeMode
import com.example.data.model.UserDirectoryEntry
import com.example.data.model.UserProfile
import com.example.data.model.UserSetupOutcome
import com.example.data.security.PasswordHasher
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.async
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NomiChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val sessionUserId: String? = null,
    private val sessionUsername: String? = null
) : NomiDataSource {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    override val requiresInitialCloudSetup: Boolean = true

    private val _incomingAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 24)
    override val incomingAlerts: Flow<IncomingMessageAlert> = _incomingAlerts.asSharedFlow()

    private fun activeUserIdOrNull(): String? {
        return auth.currentUser?.uid ?: sessionUserId?.takeIf { it.isNotBlank() }
    }

    override suspend fun onNetworkReconnected() {
        runCatching { db.enableNetwork().await() }
    }

    override suspend fun verifyActiveFirebaseConnection(): Boolean {
        if (activeUserIdOrNull() == null) return false
        return try {
            db.enableNetwork()
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun isVerifiedAdminSession(): Boolean {
        val user = auth.currentUser ?: return false
        return user.isEmailVerified && user.email.equals(ADMIN_GMAIL, ignoreCase = true)
    }

    private fun requireVerifiedAdmin(): String {
        val user = auth.currentUser
        if (user == null || !user.isEmailVerified || !user.email.equals(ADMIN_GMAIL, ignoreCase = true)) {
            throw SecurityException("Admin access is locked to verified $ADMIN_GMAIL.")
        }
        return ADMIN_GMAIL
    }

    private fun requireUserId(): String {
        return activeUserIdOrNull()
            ?: throw IllegalStateException("User must be signed in before accessing Firestore.")
    }

    private fun sanitizeHandle(raw: String): String {
        val cleaned = normalizeUsername(raw)
        return cleaned.ifBlank { "nomiuser" }.take(40)
    }

    private fun isValidFirestoreId(id: String): Boolean {
        return id.isNotBlank() && id.length <= 128 && !id.contains('/')
    }

    private suspend fun getFreshSnapshot(
        ref: DocumentReference,
        serverTimeoutMs: Long = 1400L
    ): DocumentSnapshot? {
        val fromServer = try {
            withTimeoutOrNull(serverTimeoutMs) {
                ref.get(Source.SERVER).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fromServer != null) return fromServer
        return try {
            withTimeoutOrNull(900L) {
                ref.get().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getCachedThenFastSnapshot(
        ref: DocumentReference,
        serverTimeoutMs: Long = 900L
    ): DocumentSnapshot? {
        val cached = try {
            ref.get(Source.CACHE).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (cached != null && cached.exists()) return cached
        return getFreshSnapshot(ref, serverTimeoutMs)
    }

    private suspend fun commitWithFastQueue(
        task: com.google.android.gms.tasks.Task<Void>,
        maxWaitMs: Long = 1400L
    ) {
        val outcome = withTimeoutOrNull(maxWaitMs) {
            runCatching { task.await() }
        }
        if (outcome != null && outcome.isFailure) {
            throw outcome.exceptionOrNull()!!
        }
    }

    private fun pickFresherDoc(a: DocumentSnapshot?, b: DocumentSnapshot?): DocumentSnapshot? {
        val aValid = a?.takeIf { it.exists() }
        val bValid = b?.takeIf { it.exists() }
        if (aValid == null) return bValid
        if (bValid == null) return aValid
        val aTs = aValid.getTimestamp("updatedAt")
        val bTs = bValid.getTimestamp("updatedAt")
        val aSec = aTs?.seconds ?: 0L
        val aNano = aTs?.nanoseconds ?: 0
        val bSec = bTs?.seconds ?: 0L
        val bNano = bTs?.nanoseconds ?: 0
        return if (aSec > bSec || (aSec == bSec && aNano >= bNano)) aValid else bValid
    }

    private fun mergeFresherDirectoryEntry(
        a: UserDirectoryEntry?,
        b: UserDirectoryEntry?
    ): UserDirectoryEntry? {
        if (a == null) return b
        if (b == null) return a
        val aSec = a.updatedAt?.seconds ?: 0L
        val aNano = a.updatedAt?.nanoseconds ?: 0
        val bSec = b.updatedAt?.seconds ?: 0L
        val bNano = b.updatedAt?.nanoseconds ?: 0
        val aIsNewer = aSec > bSec || (aSec == bSec && aNano >= bNano)
        val newer = if (aIsNewer) a else b
        val older = if (aIsNewer) b else a
        return newer.copy(
            ownerUid = newer.ownerUid?.takeIf { it.isNotBlank() } ?: older.ownerUid,
            photoData = newer.photoData?.takeIf { it.isNotBlank() } ?: older.photoData,
            bannerData = newer.bannerData?.takeIf { it.isNotBlank() } ?: older.bannerData
        )
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
                    if (toLog != null && activeUserIdOrNull() == uid) {
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
                    if (toLog != null && activeUserIdOrNull() != null) {
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
                if (toLog != null && activeUserIdOrNull() == uid) {
                    handleFirestoreError(toLog, OperationType.LIST, path)
                }
                throw error
            }

        emitAll(
            channelFlow {
                val latestContacts = MutableStateFlow<List<ChatThread>?>(null)
                val directoryEntries = MutableStateFlow<Map<String, UserDirectoryEntry>>(emptyMap())
                val publicProfileEntries = MutableStateFlow<Map<String, UserDirectoryEntry>>(emptyMap())
                val trackedHandles = MutableStateFlow<List<String>>(emptyList())
                val trackedPeerUids = MutableStateFlow<List<String>>(emptyList())

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
                            .toList()
                        if (peerHandles != trackedHandles.value) {
                            trackedHandles.value = peerHandles
                        }
                    }
                }

                launch {
                    combine(latestContacts, directoryEntries) { threads, dirMap ->
                        val fromThreads = threads.orEmpty()
                            .asSequence()
                            .filter { it.threadType == "chat" && it.peerUid.isNotBlank() }
                            .map { it.peerUid.trim() }
                        val fromDir = dirMap.values
                            .asSequence()
                            .mapNotNull { it.ownerUid?.trim()?.takeIf { peerId -> peerId.isNotBlank() } }
                        (fromThreads + fromDir)
                            .distinct()
                            .sorted()
                            .toList()
                    }.collect { peerUids ->
                        if (peerUids != trackedPeerUids.value) {
                            trackedPeerUids.value = peerUids
                        }
                    }
                }

                val activePublicChunkListeners = mutableMapOf<List<String>, kotlinx.coroutines.Job>()
                val activeDirectoryChunkListeners = mutableMapOf<List<String>, kotlinx.coroutines.Job>()

                launch {
                    trackedPeerUids.collect { peerUids ->
                        val targetChunks = peerUids.distinct().chunked(30).toSet()
                        val toRemove = activePublicChunkListeners.keys - targetChunks
                        for (removedChunk in toRemove) {
                            activePublicChunkListeners.remove(removedChunk)?.cancel()
                        }
                        publicProfileEntries.update { current ->
                            current.filterKeys { it in peerUids }
                        }
                        for (chunk in targetChunks) {
                            if (!activePublicChunkListeners.containsKey(chunk)) {
                                activePublicChunkListeners[chunk] = launch {
                                    db.collection("public_profiles")
                                        .whereIn(FieldPath.documentId(), chunk)
                                        .limit(30)
                                        .snapshots()
                                        .catch { err ->
                                            if (err is CancellationException) throw err
                                        }
                                        .collect { snap ->
                                            val chunkEntries = mutableMapOf<String, UserDirectoryEntry>()
                                            for (doc in snap.documents) {
                                                val entry = doc.toObject(
                                                    UserDirectoryEntry::class.java,
                                                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                                                )
                                                if (entry != null) {
                                                    chunkEntries[doc.id] = entry
                                                }
                                            }
                                            publicProfileEntries.update { current ->
                                                (current - chunk.toSet()) + chunkEntries
                                            }
                                        }
                                }
                            }
                        }
                    }
                }

                launch {
                    trackedHandles.collect { handles ->
                        val targetChunks = handles.distinct().chunked(30).toSet()
                        val toRemove = activeDirectoryChunkListeners.keys - targetChunks
                        for (removedChunk in toRemove) {
                            activeDirectoryChunkListeners.remove(removedChunk)?.cancel()
                        }
                        directoryEntries.update { current ->
                            current.filterKeys { it in handles }
                        }
                        for (chunk in targetChunks) {
                            if (!activeDirectoryChunkListeners.containsKey(chunk)) {
                                activeDirectoryChunkListeners[chunk] = launch {
                                    db.collection("directory")
                                        .whereIn(FieldPath.documentId(), chunk)
                                        .limit(30)
                                        .snapshots()
                                        .catch { err ->
                                            if (err is CancellationException) throw err
                                        }
                                        .collect { snap ->
                                            val chunkEntries = mutableMapOf<String, UserDirectoryEntry>()
                                            for (doc in snap.documents) {
                                                val entry = doc.toObject(
                                                    UserDirectoryEntry::class.java,
                                                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                                                )
                                                if (entry != null) {
                                                    chunkEntries[doc.id] = entry
                                                }
                                            }
                                            directoryEntries.update { current ->
                                                (current - chunk.toSet()) + chunkEntries
                                            }
                                        }
                                }
                            }
                        }
                    }
                }

                launch {
                    combine(latestContacts, publicProfileEntries, directoryEntries) { contacts, pubMap, dirMap ->
                        contacts?.map { thread ->
                            val entry = if (thread.threadType == "chat") {
                                val byHandle = thread.username.takeIf { it.isNotBlank() }?.let { dirMap[sanitizeHandle(it)] }
                                val resolvedPeerUid = thread.peerUid.takeIf { it.isNotBlank() }
                                    ?: byHandle?.ownerUid?.takeIf { it.isNotBlank() }
                                    ?: ""
                                val byUid = resolvedPeerUid.takeIf { it.isNotBlank() }?.let { pubMap[it] }
                                mergeFresherDirectoryEntry(byUid, byHandle)
                            } else null
                            mergeThreadWithDirectory(
                                thread = thread,
                                entry = entry,
                                viewerUid = uid
                            )
                        }
                    }.collect { merged ->
                        if (merged != null) {
                            trySend(merged)
                        }
                    }
                }

                awaitClose {
                    activePublicChunkListeners.values.forEach { it.cancel() }
                    activeDirectoryChunkListeners.values.forEach { it.cancel() }
                    activePublicChunkListeners.clear()
                    activeDirectoryChunkListeners.clear()
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
                    if (toLog != null && activeUserIdOrNull() == uid) {
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
                    if (toLog != null && activeUserIdOrNull() == uid) {
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
                    if (toLog != null && activeUserIdOrNull() == uid) {
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
            val authorPhotoData = userSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
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
            val viewerPhoto = userSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }

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
            val viewerPhoto = userSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }

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
        val uid = activeUserIdOrNull() ?: return
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
            val existingUser = getFreshSnapshot(userRef, 3000L) ?: userRef.get().await()

            if (existingUser.exists()) {
                val status = existingUser.getString("accountStatus") ?: "active"
                if (status == "banned" || existingUser.getBoolean("bannedByAdmin") == true) {
                    return Result.success(UserSetupOutcome.Banned)
                }
                val shouldBeVerified = (existingUser.getBoolean("verified") == true) || isVerifiedAdminSession()
                if (existingUser.getBoolean("verified") != shouldBeVerified) {
                    runCatching {
                        userRef.update(
                            mapOf(
                                "verified" to shouldBeVerified,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }
                val existingHandle = sanitizeHandle(existingUser.getString("username").orEmpty())
                val existingDisplayName = existingUser.getString("displayName").orEmpty().ifBlank { "Nomi User" }
                val existingBio = existingUser.getString("bio").orEmpty().take(200)
                val existingProvider = existingUser.getString("provider").orEmpty().ifBlank { "google" }
                val existingPhotoData = existingUser.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                val existingBannerData = existingUser.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                val existingVisibility = ProfileVisibilityMode.fromKey(existingUser.getString("profileVisibility")).key
                @Suppress("UNCHECKED_CAST")
                val existingAllowedViewers = (existingUser.get("allowedViewerUids") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.take(500)
                    ?: emptyList()
                val existingMaxViewers = (existingUser.getLong("maxProfileViewers") ?: 0L).coerceIn(0L, 500L)
                if (existingHandle.isNotBlank()) {
                    val existingDirRef = db.collection("directory").document(existingHandle)
                    val existingDirSnap = getFreshSnapshot(existingDirRef, 2500L) ?: existingDirRef.get().await()
                    if (existingDirSnap.exists() &&
                        (existingDirSnap.getString("accountStatus") == "banned" ||
                            existingDirSnap.getBoolean("bannedByAdmin") == true)
                    ) {
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
                            "showOnlineStatus" to (existingUser.getBoolean("showOnlineStatus") ?: true),
                            "verified" to shouldBeVerified,
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
                        val dirVerified = existingDirSnap.getBoolean("verified")
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
                        if (dirVerified != shouldBeVerified) {
                            dirSync["verified"] = shouldBeVerified
                        }
                        if (dirSync.isNotEmpty()) {
                            dirSync["updatedAt"] = FieldValue.serverTimestamp()
                            runCatching { existingDirRef.update(dirSync).await() }
                        }
                    }
                    if (existingHandle.isNotBlank()) {
                        val existingUsernameRef = db.collection("usernames").document(existingHandle)
                        val existingUsernameSnap = getFreshSnapshot(existingUsernameRef, 2500L)
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
                        } else if (existingUsernameSnap != null &&
                            existingUsernameSnap.getString("status") == "retired" &&
                            existingUsernameSnap.getString("ownerUid").isNullOrBlank()
                        ) {
                            runCatching {
                                existingUsernameRef.update(
                                    mapOf(
                                        "ownerUid" to uid,
                                        "status" to "active",
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                ).await()
                            }
                        }
                        syncPublicProfile(
                            uid = uid,
                            username = existingHandle,
                            displayName = existingDisplayName.take(60),
                            provider = existingProvider,
                            bio = existingBio,
                            photoData = existingPhotoData,
                            bannerData = existingBannerData,
                            profileVisibility = existingVisibility,
                            allowedViewerUids = existingAllowedViewers,
                            maxProfileViewers = existingMaxViewers,
                            online = existingUser.getBoolean("online") ?: true,
                            showOnlineStatus = existingUser.getBoolean("showOnlineStatus") ?: true,
                            verified = shouldBeVerified
                        )
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
                                    "verified" to shouldBeVerified,
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
                        val requestedUsernameSnap = getFreshSnapshot(requestedUsernameRef, 3000L)
                            ?: requestedUsernameRef.get().await()
                        val requestedDirRef = db.collection("directory").document(cleanUser)
                        val requestedDirSnap = getFreshSnapshot(requestedDirRef, 3000L)
                            ?: requestedDirRef.get().await()
                        val takenInUsernames = requestedUsernameSnap.exists() &&
                            requestedUsernameSnap.getString("status") == "active" &&
                            requestedUsernameSnap.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }
                        val takenInDir = requestedDirSnap.exists() &&
                            requestedDirSnap.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }
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
                                "verified" to shouldBeVerified,
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
                        } else if (requestedUsernameSnap.getString("status") != "active" ||
                            requestedUsernameSnap.getString("ownerUid") != uid
                        ) {
                            batch.update(
                                requestedUsernameRef,
                                mapOf(
                                    "ownerUid" to uid,
                                    "status" to "active",
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
                                    "verified" to shouldBeVerified,
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
                        syncPublicProfile(
                            uid = uid,
                            username = cleanUser,
                            displayName = cleanName,
                            provider = existingUser.getString("provider") ?: "username",
                            bio = existingBio,
                            photoData = existingPhotoData,
                            bannerData = existingBannerData,
                            profileVisibility = existingVisibility,
                            allowedViewerUids = existingAllowedViewers,
                            maxProfileViewers = existingMaxViewers,
                            online = true,
                            showOnlineStatus = existingUser.getBoolean("showOnlineStatus") ?: true,
                            verified = shouldBeVerified
                        )
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
            val usernameSnapCandidate = getFreshSnapshot(usernameRefCandidate, 3000L)
                ?: usernameRefCandidate.get().await()
            val dirRefCandidate = db.collection("directory").document(baseHandle)
            val dirSnapCandidate = getFreshSnapshot(dirRefCandidate, 3000L)
                ?: dirRefCandidate.get().await()

            val resolvedUsername: String
            val dirRefToCreate: DocumentReference?
            val usernameRefToCreate: DocumentReference?
            val usernameRefExists: Boolean

            val candidateResTaken = usernameSnapCandidate.exists() &&
                usernameSnapCandidate.getString("status") == "active" &&
                usernameSnapCandidate.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }
            val isUsernameUnavailable = candidateResTaken || dirSnapCandidate.exists()

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
                        usernameSnapCandidate.getString("ownerUid") == uid ||
                        usernameSnapCandidate.getString("status") != "active")
                ) {
                    resolvedUsername = baseHandle
                    dirRefToCreate = null
                    val alreadyOwned = usernameSnapCandidate.exists() &&
                        usernameSnapCandidate.getString("status") == "active" &&
                        usernameSnapCandidate.getString("ownerUid") == uid
                    usernameRefToCreate = if (alreadyOwned) null else usernameRefCandidate
                    usernameRefExists = usernameSnapCandidate.exists()
                } else {
                    val fallbackHandle = "${baseHandle.take(30)}_${uid.take(6).lowercase()}"
                    val fallbackUsernameRef = db.collection("usernames").document(fallbackHandle)
                    val fallbackUsernameSnap = getFreshSnapshot(fallbackUsernameRef, 2500L)
                        ?: fallbackUsernameRef.get().await()
                    val fallbackRef = db.collection("directory").document(fallbackHandle)
                    val fallbackSnap = getFreshSnapshot(fallbackRef, 2500L)
                        ?: fallbackRef.get().await()
                    val fallbackBusy = (fallbackUsernameSnap.exists() &&
                        fallbackUsernameSnap.getString("status") == "active" &&
                        fallbackUsernameSnap.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }) ||
                        (fallbackSnap.exists() && fallbackSnap.getString("ownerUid") != uid)
                    if (fallbackBusy) {
                        val uniqueHandle = "${baseHandle.take(24)}_${(System.currentTimeMillis() % 100000)}"
                        resolvedUsername = uniqueHandle
                        dirRefToCreate = db.collection("directory").document(uniqueHandle)
                        usernameRefToCreate = db.collection("usernames").document(uniqueHandle)
                        usernameRefExists = false
                    } else {
                        resolvedUsername = fallbackHandle
                        dirRefToCreate = if (fallbackSnap.exists()) null else fallbackRef
                        val alreadyOwned = fallbackUsernameSnap.exists() &&
                            fallbackUsernameSnap.getString("status") == "active" &&
                            fallbackUsernameSnap.getString("ownerUid") == uid
                        usernameRefToCreate = if (alreadyOwned) null else fallbackUsernameRef
                        usernameRefExists = fallbackUsernameSnap.exists()
                    }
                }
            } else {
                resolvedUsername = baseHandle
                dirRefToCreate = dirRefCandidate
                val alreadyOwned = usernameSnapCandidate.exists() &&
                    usernameSnapCandidate.getString("status") == "active" &&
                    usernameSnapCandidate.getString("ownerUid") == uid
                usernameRefToCreate = if (alreadyOwned) null else usernameRefCandidate
                usernameRefExists = usernameSnapCandidate.exists()
            }

            val resolvedDob = preferredDob.trim().ifBlank { "2000-01-01" }.take(20)
            val resolvedProvider = if (authFlowMode == AuthFlowMode.SIGN_UP || auth.currentUser == null) "username" else "google"
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
                usernameRefExists = usernameRefExists,
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
        userRef: DocumentReference,
        resolvedName: String,
        resolvedUsername: String,
        resolvedEmail: String,
        resolvedDob: String,
        resolvedProvider: String,
        dirRef: DocumentReference?,
        usernameRef: DocumentReference? = null,
        usernameRefExists: Boolean = false,
        credentialSaltAndHash: Pair<String, String>? = null
    ) {
        val batch = db.batch()
        val defaultBio = "Dream Big... 💚"
        val isVerified = isVerifiedAdminSession()
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
            "verified" to isVerified,
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
            if (usernameRefExists) {
                batch.update(
                    usernameRef,
                    mapOf(
                        "ownerUid" to uid,
                        "status" to "active",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
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
                    "verified" to isVerified,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "reportedByUids" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        val pubRef = db.collection("public_profiles").document(uid)
        batch.set(
            pubRef,
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
                "verified" to isVerified,
                "accountStatus" to "active",
                "bannedByAdmin" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.commit().await()
    }

    private suspend fun syncPublicProfile(
        uid: String,
        username: String,
        displayName: String,
        provider: String,
        bio: String,
        photoData: String?,
        bannerData: String?,
        profileVisibility: String,
        allowedViewerUids: List<String>,
        maxProfileViewers: Long,
        online: Boolean,
        showOnlineStatus: Boolean,
        verified: Boolean = isVerifiedAdminSession()
    ) {
        val cleanHandle = sanitizeHandle(username)
        if (cleanHandle.isBlank()) return
        runCatching {
            val pubRef = db.collection("public_profiles").document(uid)
            val snap = getFreshSnapshot(pubRef, 2500L) ?: pubRef.get().await()
            if (!snap.exists()) {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "username" to cleanHandle,
                    "displayName" to displayName.ifBlank { "Nomi User" }.take(60),
                    "provider" to provider.ifBlank { "google" },
                    "bio" to bio.take(200),
                    "profileVisibility" to ProfileVisibilityMode.fromKey(profileVisibility).key,
                    "allowedViewerUids" to allowedViewerUids.take(500),
                    "maxProfileViewers" to maxProfileViewers.coerceIn(0L, 500L),
                    "online" to online,
                    "showOnlineStatus" to showOnlineStatus,
                    "verified" to verified,
                    "accountStatus" to "active",
                    "bannedByAdmin" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoData.isNullOrBlank() && photoData.length <= 250000) payload["photoData"] = photoData
                if (!bannerData.isNullOrBlank() && bannerData.length <= 250000) payload["bannerData"] = bannerData
                pubRef.set(payload).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "username" to cleanHandle,
                    "displayName" to displayName.ifBlank { "Nomi User" }.take(60),
                    "bio" to bio.take(200),
                    "profileVisibility" to ProfileVisibilityMode.fromKey(profileVisibility).key,
                    "allowedViewerUids" to allowedViewerUids.take(500),
                    "maxProfileViewers" to maxProfileViewers.coerceIn(0L, 500L),
                    "online" to online,
                    "showOnlineStatus" to showOnlineStatus,
                    "verified" to verified,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoData.isNullOrBlank() && photoData.length <= 250000) updates["photoData"] = photoData
                if (!bannerData.isNullOrBlank() && bannerData.length <= 250000) updates["bannerData"] = bannerData
                pubRef.update(updates).await()
            }
        }
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
            runCatching { db.enableNetwork() }
            val isVerified = isVerifiedAdminSession()

            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isVerified) {
                updates["verified"] = true
            }
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
                require(it.length <= 250000) { "Profile photo is too large." }
                updates["photoData"] = it
            }
            bannerData?.let {
                require(it.length <= 250000) { "Profile banner is too large." }
                updates["bannerData"] = it
            }

            val userRef = db.collection("users").document(uid)
            var currentUserSnap = if (requestedHandle == null) {
                getCachedThenFastSnapshot(userRef, 900L)?.takeIf { it.exists() }
            } else {
                getFreshSnapshot(userRef, 1600L)?.takeIf { it.exists() }
            }
            if (currentUserSnap == null || !currentUserSnap.exists()) {
                val currentAuth = auth.currentUser
                ensureInitialUserSetup(
                    userId = uid,
                    googleDisplayName = currentAuth?.displayName,
                    googleEmail = currentAuth?.email,
                    preferredDisplayName = displayName?.trim().orEmpty().ifBlank { sessionUsername.orEmpty() },
                    preferredUsername = username?.trim().orEmpty().ifBlank { sessionUsername.orEmpty() },
                    preferredDob = dob?.trim().orEmpty(),
                    authFlowMode = if (currentAuth == null) AuthFlowMode.RETURNING_LOGIN else AuthFlowMode.GOOGLE_QUICK,
                    returningIdentifier = sessionUsername.orEmpty()
                )
                currentUserSnap = getCachedThenFastSnapshot(userRef, 1000L)?.takeIf { it.exists() }
                    ?: userRef.get().await()
            }
            val effectiveVerified = (currentUserSnap.getBoolean("verified") == true) || isVerified
            val currentHandle = currentUserSnap.getString("username").orEmpty()
            val previousHandle = currentHandle.takeIf { it.isNotBlank() }?.let { sanitizeHandle(it) }.orEmpty()
            val targetNewHandle = requestedHandle

            if (targetNewHandle != null && targetNewHandle != previousHandle) {
                val (existingReservation, existingDir) = coroutineScope {
                    val resTask = async { getFreshSnapshot(db.collection("usernames").document(targetNewHandle), 1800L) }
                    val dirTask = async { getFreshSnapshot(db.collection("directory").document(targetNewHandle), 1800L) }
                    resTask.await() to dirTask.await()
                }
                val resTaken = existingReservation != null &&
                    existingReservation.exists() &&
                    existingReservation.getString("status") == "active" &&
                    existingReservation.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }
                if (resTaken) {
                    return Result.failure(
                        IllegalStateException("This username is already taken / already added.")
                    )
                }
                val dirTaken = existingDir != null &&
                    existingDir.exists() &&
                    existingDir.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }
                if (dirTaken) {
                    return Result.failure(
                        IllegalStateException("This username is already taken / already added.")
                    )
                }
            }

            val currentDisplayName = currentUserSnap.getString("displayName").orEmpty().ifBlank { "Nomi User" }
            val currentBio = currentUserSnap.getString("bio").orEmpty().take(200)
            val currentDob = currentUserSnap.getString("dob").orEmpty().ifBlank { "2000-01-01" }.take(20)
            val currentProvider = currentUserSnap.getString("provider").orEmpty().ifBlank { "google" }
            val currentPhotoData = currentUserSnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
            val currentBannerData = currentUserSnap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
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
                val pubRef = db.collection("public_profiles").document(uid)
                val oldUsernameRef = previousHandle.takeIf { it.isNotBlank() }?.let { db.collection("usernames").document(it) }
                val oldDirRef = previousHandle.takeIf { it.isNotBlank() }?.let { db.collection("directory").document(it) }
                val oldCredRef = previousHandle.takeIf { it.isNotBlank() }?.let { userRef.collection("credentials").document(it) }

                val newAccountRef = db.collection("username_accounts").document(targetNewHandle)
                val oldAccountRef = previousHandle.takeIf { it.isNotBlank() }
                    ?.let { db.collection("username_accounts").document(it) }

                db.runTransaction { tx ->
                    // All transaction reads must happen before any writes
                    val newUsernameSnap = tx.get(newUsernameRef)
                    val newDirSnap = tx.get(newDirRef)
                    val newAccountSnap = tx.get(newAccountRef)
                    val pubSnap = tx.get(pubRef)
                    val oldUsernameSnap = oldUsernameRef?.let { tx.get(it) }
                    val oldDirSnap = oldDirRef?.let { tx.get(it) }
                    val oldCredSnap = oldCredRef?.let { tx.get(it) }
                    val oldAccountSnap = oldAccountRef?.let { tx.get(it) }

                    val resActiveByOther = newUsernameSnap.exists() &&
                        newUsernameSnap.getString("status") == "active" &&
                        newUsernameSnap.getString("ownerUid").orEmpty().let { it.isNotBlank() && it != uid }
                    if (resActiveByOther) {
                        throw IllegalStateException("This username is already taken / already added.")
                    }
                    if (newDirSnap.exists() && newDirSnap.getString("ownerUid") != uid) {
                        throw IllegalStateException("This username is already taken / already added.")
                    }
                    if (newAccountSnap.exists() && newAccountSnap.getString("userId") != uid) {
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
                    } else if (newUsernameSnap.getString("status") != "active" ||
                        newUsernameSnap.getString("ownerUid") != uid
                    ) {
                        tx.update(
                            newUsernameRef,
                            mapOf(
                                "ownerUid" to uid,
                                "status" to "active",
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
                            "verified" to effectiveVerified,
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
                            "verified" to effectiveVerified,
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

                    if (!pubSnap.exists()) {
                        val pubPayload = mutableMapOf<String, Any>(
                            "ownerUid" to uid,
                            "username" to targetNewHandle,
                            "displayName" to updatedName.take(60),
                            "provider" to currentProvider,
                            "bio" to updatedBio.take(200),
                            "profileVisibility" to currentVisibility,
                            "allowedViewerUids" to currentAllowedViewers,
                            "maxProfileViewers" to currentMaxViewers,
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "verified" to effectiveVerified,
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) pubPayload["photoData"] = updatedPhotoData
                        if (!updatedBannerData.isNullOrBlank()) pubPayload["bannerData"] = updatedBannerData
                        tx.set(pubRef, pubPayload)
                    } else {
                        val pubUpdates = mutableMapOf<String, Any>(
                            "username" to targetNewHandle,
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "profileVisibility" to currentVisibility,
                            "allowedViewerUids" to currentAllowedViewers,
                            "maxProfileViewers" to currentMaxViewers,
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "verified" to effectiveVerified,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) pubUpdates["photoData"] = updatedPhotoData
                        if (!updatedBannerData.isNullOrBlank()) pubUpdates["bannerData"] = updatedBannerData
                        tx.update(pubRef, pubUpdates)
                    }

                    // Release old username as retired/unowned so it stops matching searches until claimed again
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
                    if (uid.startsWith("usr_") && oldAccountRef != null && oldAccountSnap != null && oldAccountSnap.exists()) {
                        val salt = oldAccountSnap.getString("passwordSalt").orEmpty()
                        val hash = oldAccountSnap.getString("passwordHash").orEmpty()
                        val status = oldAccountSnap.getString("accountStatus").orEmpty().ifBlank { "active" }
                        val bannedAdmin = oldAccountSnap.getBoolean("bannedByAdmin") ?: false
                        if (salt.length >= 16 && hash.length == 64) {
                            tx.set(
                                newAccountRef,
                                mapOf(
                                    "userId" to uid,
                                    "username" to targetNewHandle,
                                    "displayName" to updatedName.take(60),
                                    "dob" to updatedDob,
                                    "passwordSalt" to salt,
                                    "passwordHash" to hash,
                                    "accountStatus" to status,
                                    "bannedByAdmin" to bannedAdmin,
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                            tx.delete(oldAccountRef)
                        }
                    }
                    null
                }.await()
            } else {
                val dirRef = updatedHandle.takeIf { it.isNotBlank() && updatedName.isNotBlank() }
                    ?.let { db.collection("directory").document(it) }
                val pubRef = updatedHandle.takeIf { it.isNotBlank() }
                    ?.let { db.collection("public_profiles").document(uid) }
                val oldCredRef = previousHandle.takeIf {
                    it.isNotBlank() && (updates.containsKey("displayName") || updates.containsKey("dob"))
                }?.let { userRef.collection("credentials").document(it) }
                val acctRef = updatedHandle.takeIf {
                    uid.startsWith("usr_") && it.isNotBlank() &&
                        (updates.containsKey("displayName") || updates.containsKey("dob"))
                }?.let { db.collection("username_accounts").document(it) }

                val (dirSnap, pubSnap, oldCredSnap, acctSnap) = coroutineScope {
                    val dirTask = async {
                        dirRef?.let { ref -> getCachedThenFastSnapshot(ref, 1500L) }
                    }
                    val pubTask = async {
                        pubRef?.let { ref -> getCachedThenFastSnapshot(ref, 1500L) }
                    }
                    val credTask = async {
                        oldCredRef?.let { ref -> getCachedThenFastSnapshot(ref, 1000L) }
                    }
                    val acctTask = async {
                        acctRef?.let { ref -> getCachedThenFastSnapshot(ref, 1000L) }
                    }
                    listOf(dirTask.await(), pubTask.await(), credTask.await(), acctTask.await())
                }

                val batch = db.batch()
                batch.update(userRef, updates)

                if (dirRef != null && updatedHandle.isNotBlank() && updatedName.isNotBlank()) {
                    if (dirSnap == null || !dirSnap.exists()) {
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
                            "verified" to effectiveVerified,
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) dirPayload["photoData"] = updatedPhotoData
                        if (!updatedBannerData.isNullOrBlank()) dirPayload["bannerData"] = updatedBannerData
                        batch.set(dirRef, dirPayload)
                    } else if (dirSnap.getString("ownerUid") == uid) {
                        val dirUpdates = mutableMapOf<String, Any>(
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "verified" to ((dirSnap.getBoolean("verified") == true) || effectiveVerified),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) dirUpdates["photoData"] = updatedPhotoData
                        if (!updatedBannerData.isNullOrBlank()) dirUpdates["bannerData"] = updatedBannerData
                        batch.update(dirRef, dirUpdates)
                    }
                }

                if (pubRef != null && updatedHandle.isNotBlank()) {
                    if (pubSnap == null || !pubSnap.exists()) {
                        val pubPayload = mutableMapOf<String, Any>(
                            "ownerUid" to uid,
                            "username" to updatedHandle,
                            "displayName" to updatedName.take(60),
                            "provider" to currentProvider,
                            "bio" to updatedBio.take(200),
                            "profileVisibility" to currentVisibility,
                            "allowedViewerUids" to currentAllowedViewers,
                            "maxProfileViewers" to currentMaxViewers,
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "verified" to effectiveVerified,
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) pubPayload["photoData"] = updatedPhotoData
                        if (!updatedBannerData.isNullOrBlank()) pubPayload["bannerData"] = updatedBannerData
                        batch.set(pubRef, pubPayload)
                    } else {
                        val pubUpdates = mutableMapOf<String, Any>(
                            "username" to updatedHandle,
                            "displayName" to updatedName.take(60),
                            "bio" to updatedBio.take(200),
                            "profileVisibility" to currentVisibility,
                            "allowedViewerUids" to currentAllowedViewers,
                            "maxProfileViewers" to currentMaxViewers,
                            "online" to currentOnline,
                            "showOnlineStatus" to currentShowOnlineStatus,
                            "verified" to ((pubSnap.getBoolean("verified") == true) || effectiveVerified),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!updatedPhotoData.isNullOrBlank()) pubUpdates["photoData"] = updatedPhotoData
                        if (!updatedBannerData.isNullOrBlank()) pubUpdates["bannerData"] = updatedBannerData
                        batch.update(pubRef, pubUpdates)
                    }
                }

                if (oldCredRef != null && oldCredSnap != null && oldCredSnap.exists()) {
                    batch.update(
                        oldCredRef,
                        mapOf(
                            "displayName" to updatedName.take(60),
                            "dob" to updatedDob,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                if (acctRef != null && acctSnap != null && acctSnap.exists() && acctSnap.getString("userId") == uid) {
                    batch.update(
                        acctRef,
                        mapOf(
                            "displayName" to updatedName.take(60),
                            "dob" to updatedDob,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                commitWithFastQueue(batch.commit(), maxWaitMs = 2200L)
            }

            val renamedHandle = if (targetNewHandle != null && targetNewHandle != previousHandle) targetNewHandle else null
            Result.success(renamedHandle)
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
            runCatching { db.enableNetwork() }
            val isVerified = isVerifiedAdminSession()
            val cleanVisibility = ProfileVisibilityMode.fromKey(profileVisibility).key
            val cleanAllowed = allowedViewerUids
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 128 }
                .distinct()
                .take(500)
            val cleanMax = maxProfileViewers.coerceIn(0L, 500L)

            val userRef = db.collection("users").document(uid)
            val snap = getFreshSnapshot(userRef, 3000L)?.takeIf { it.exists() }
                ?: userRef.get().await()
            val effectiveVerified = (snap.getBoolean("verified") == true) || isVerified
            val handle = sanitizeHandle(snap.getString("username").orEmpty())
            val displayName = snap.getString("displayName").orEmpty().ifBlank { "Nomi User" }.take(60)
            val bio = snap.getString("bio").orEmpty().take(200)
            val provider = snap.getString("provider").orEmpty().ifBlank { "google" }
            val photoData = snap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
            val bannerData = snap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }

            val dirRef = handle.takeIf { it.isNotBlank() }?.let { db.collection("directory").document(it) }
            val pubRef = handle.takeIf { it.isNotBlank() }?.let { db.collection("public_profiles").document(uid) }
            val (dirSnap, pubSnap) = coroutineScope {
                val dirTask = async { dirRef?.let { ref -> getFreshSnapshot(ref, 2500L) } }
                val pubTask = async { pubRef?.let { ref -> getFreshSnapshot(ref, 2500L) } }
                dirTask.await() to pubTask.await()
            }

            val batch = db.batch()
            batch.update(
                userRef,
                mapOf(
                    "profileVisibility" to cleanVisibility,
                    "allowedViewerUids" to cleanAllowed,
                    "maxProfileViewers" to cleanMax,
                    "showOnlineStatus" to showOnlineStatus,
                    "online" to showOnlineStatus,
                    "verified" to effectiveVerified,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            if (dirRef != null && handle.isNotBlank()) {
                if (dirSnap == null || !dirSnap.exists()) {
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
                        "verified" to effectiveVerified,
                        "accountStatus" to "active",
                        "reportCount" to 0L,
                        "reportedByUids" to emptyList<String>(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (!photoData.isNullOrBlank()) dirPayload["photoData"] = photoData
                    if (!bannerData.isNullOrBlank()) dirPayload["bannerData"] = bannerData
                    batch.set(dirRef, dirPayload)
                } else if (dirSnap.getString("ownerUid") == uid) {
                    batch.update(
                        dirRef,
                        mapOf(
                            "profileVisibility" to cleanVisibility,
                            "allowedViewerUids" to cleanAllowed,
                            "maxProfileViewers" to cleanMax,
                            "showOnlineStatus" to showOnlineStatus,
                            "online" to showOnlineStatus,
                            "verified" to ((dirSnap.getBoolean("verified") == true) || effectiveVerified),
                            "bio" to bio,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }

            if (pubRef != null && handle.isNotBlank()) {
                if (pubSnap == null || !pubSnap.exists()) {
                    val pubPayload = mutableMapOf<String, Any>(
                        "ownerUid" to uid,
                        "username" to handle,
                        "displayName" to displayName,
                        "provider" to provider,
                        "bio" to bio,
                        "profileVisibility" to cleanVisibility,
                        "allowedViewerUids" to cleanAllowed,
                        "maxProfileViewers" to cleanMax,
                        "online" to showOnlineStatus,
                        "showOnlineStatus" to showOnlineStatus,
                        "verified" to effectiveVerified,
                        "accountStatus" to "active",
                        "bannedByAdmin" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (!photoData.isNullOrBlank()) pubPayload["photoData"] = photoData
                    if (!bannerData.isNullOrBlank()) pubPayload["bannerData"] = bannerData
                    batch.set(pubRef, pubPayload)
                } else {
                    val pubUpdates = mutableMapOf<String, Any>(
                        "username" to handle,
                        "displayName" to displayName,
                        "bio" to bio,
                        "profileVisibility" to cleanVisibility,
                        "allowedViewerUids" to cleanAllowed,
                        "maxProfileViewers" to cleanMax,
                        "online" to showOnlineStatus,
                        "showOnlineStatus" to showOnlineStatus,
                        "verified" to ((pubSnap.getBoolean("verified") == true) || effectiveVerified),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (!photoData.isNullOrBlank()) pubUpdates["photoData"] = photoData
                    if (!bannerData.isNullOrBlank()) pubUpdates["bannerData"] = bannerData
                    batch.update(pubRef, pubUpdates)
                }
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun syncAccountSettings(
        userId: String,
        themeMode: ThemeMode?,
        notificationsEnabled: Boolean?,
        smartAiEnabled: Boolean?,
        appLockPinHash: String?
    ) {
        val uid = activeUserIdOrNull() ?: return
        if (uid != userId) return
        val updates = mutableMapOf<String, Any>("updatedAt" to FieldValue.serverTimestamp())
        themeMode?.let { updates["themeMode"] = it.name }
        notificationsEnabled?.let { updates["notificationsEnabled"] = it }
        smartAiEnabled?.let { updates["smartAiEnabled"] = it }
        appLockPinHash?.let { updates["appLockPinHash"] = it.take(128) }
        if (updates.size <= 1) return
        runCatching {
            db.collection("users").document(uid).update(updates).await()
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
            val acctRef = if (uid.startsWith("usr_")) {
                db.collection("username_accounts").document(handle)
            } else null
            val acctSnap = acctRef?.get()?.await()

            val verifyingSnap = when {
                credSnap.exists() -> credSnap
                acctSnap != null && acctSnap.exists() -> acctSnap
                else -> null
            }

            if (verifyingSnap != null) {
                require(currentPassword.isNotEmpty()) { "Enter your current password." }
                val existingSalt = verifyingSnap.getString("passwordSalt").orEmpty()
                val existingHash = verifyingSnap.getString("passwordHash").orEmpty()
                if (!PasswordHasher.verifyPassword(currentPassword, existingSalt, existingHash)) {
                    return Result.failure(IllegalArgumentException("Current password is incorrect."))
                }
            }

            val nextSalt = PasswordHasher.generateSalt()
            val nextHash = PasswordHasher.hashPassword(newPassword, nextSalt)
            val batch = db.batch()
            if (credSnap.exists()) {
                batch.update(
                    credRef,
                    mapOf(
                        "passwordSalt" to nextSalt,
                        "passwordHash" to nextHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                batch.set(
                    credRef,
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
                )
            }
            if (acctRef != null && acctSnap != null && acctSnap.exists() && acctSnap.getString("userId") == uid) {
                batch.update(
                    acctRef,
                    mapOf(
                        "passwordSalt" to nextSalt,
                        "passwordHash" to nextHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
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
                db.collection("public_profiles").document(uid).get(Source.SERVER).await()
            }
            val contactsSnap = runCatching {
                db.collection("users").document(uid).collection("contacts").get(Source.SERVER).await()
            }.getOrNull()
            if (contactsSnap != null) {
                val peerUids = contactsSnap.documents
                    .mapNotNull { it.getString("peerUid")?.trim()?.takeIf { id -> id.isNotBlank() } }
                    .distinct()
                    .take(25)
                if (peerUids.isNotEmpty()) {
                    coroutineScope {
                        for (peerUid in peerUids) {
                            launch {
                                try {
                                    withTimeoutOrNull(2500L) {
                                        db.collection("public_profiles").document(peerUid)
                                            .get(Source.SERVER).await()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
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

    override suspend fun refreshPeerProfile(userId: String, contactId: String) {
        val uid = activeUserIdOrNull() ?: return
        if (uid != userId || contactId.isBlank()) return
        try {
            val contactRef = db.collection("users").document(uid)
                .collection("contacts").document(contactId)
            val contactSnap = runCatching { contactRef.get(Source.CACHE).await() }.getOrNull()
                ?.takeIf { it.exists() }
                ?: withTimeoutOrNull(2000L) { runCatching { contactRef.get().await() }.getOrNull() }
                ?: return
            if (!contactSnap.exists() || contactSnap.getString("threadType") != "chat") return

            val initialPeerUid = contactSnap.getString("peerUid").orEmpty().trim()
            val handle = contactSnap.getString("username").orEmpty().trim()
                .takeIf { it.isNotBlank() }
                ?.let { sanitizeHandle(it) }
                .orEmpty()

            val dirSnap = if (handle.isNotBlank()) {
                withTimeoutOrNull(3000L) {
                    runCatching { db.collection("directory").document(handle).get(Source.SERVER).await() }.getOrNull()
                }
            } else null
            val resolvedPeerUid = initialPeerUid.ifBlank {
                dirSnap?.getString("ownerUid").orEmpty().trim()
            }
            val pubSnap = if (resolvedPeerUid.isNotBlank()) {
                withTimeoutOrNull(3000L) {
                    runCatching { db.collection("public_profiles").document(resolvedPeerUid).get(Source.SERVER).await() }.getOrNull()
                }
            } else null

            val primarySnap = pickFresherDoc(pubSnap, dirSnap) ?: return
            val fallbackSnap = if (primarySnap == pubSnap) dirSnap else pubSnap

            @Suppress("UNCHECKED_CAST")
            val allowedViewers = (primarySnap.get("allowedViewerUids") as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()
            val visible = canViewerSeeProfile(
                profileVisibility = primarySnap.getString("profileVisibility"),
                allowedViewerUids = allowedViewers,
                maxProfileViewers = primarySnap.getLong("maxProfileViewers") ?: 0L,
                ownerUid = primarySnap.getString("ownerUid").orEmpty().ifBlank { resolvedPeerUid },
                viewerUid = uid
            )

            val freshPhoto = if (visible) {
                primarySnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                    ?: fallbackSnap?.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
            } else null
            val freshBanner = if (visible) {
                primarySnap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                    ?: fallbackSnap?.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
            } else null
            val freshBio = if (visible) {
                primarySnap.getString("bio").orEmpty()
                    .ifBlank { fallbackSnap?.getString("bio").orEmpty() }
                    .take(200)
            } else ""
            val freshName = primarySnap.getString("displayName")?.trim()?.takeIf { it.isNotBlank() }?.take(60)
            val freshHandle = primarySnap.getString("username")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { sanitizeHandle(it) }
            val freshStatus = if (
                primarySnap.getString("accountStatus") == "banned" ||
                primarySnap.getBoolean("bannedByAdmin") == true
            ) "banned" else "active"

            val updates = mutableMapOf<String, Any>()
            if (resolvedPeerUid.isNotBlank() && contactSnap.getString("peerUid").orEmpty() != resolvedPeerUid) {
                updates["peerUid"] = resolvedPeerUid
                updates["isRegisteredPeer"] = true
            }
            if (!freshHandle.isNullOrBlank() && contactSnap.getString("username").orEmpty() != freshHandle) {
                updates["username"] = freshHandle
            }
            if (contactSnap.getBoolean("customRenamed") != true && !freshName.isNullOrBlank() &&
                contactSnap.getString("name").orEmpty() != freshName
            ) {
                updates["name"] = freshName
            }
            if (contactSnap.getString("bio").orEmpty() != freshBio) {
                updates["bio"] = freshBio
            }
            if (contactSnap.getString("accountStatus").orEmpty() != freshStatus) {
                updates["accountStatus"] = freshStatus
            }
            if (!visible) {
                if (contactSnap.contains("photoData")) updates["photoData"] = FieldValue.delete()
                if (contactSnap.contains("bannerData")) updates["bannerData"] = FieldValue.delete()
            } else {
                if (!freshPhoto.isNullOrBlank() && contactSnap.getString("photoData") != freshPhoto) {
                    updates["photoData"] = freshPhoto
                }
                if (!freshBanner.isNullOrBlank() && contactSnap.getString("bannerData") != freshBanner) {
                    updates["bannerData"] = freshBanner
                }
            }

            if (updates.isNotEmpty()) {
                updates["updatedAt"] = FieldValue.serverTimestamp()
                contactRef.update(updates).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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
            val (usernameSnap, snap) = coroutineScope {
                val uTask = async { getFreshSnapshot(db.collection("usernames").document(cleanHandle), 1200L) }
                val dTask = async { getFreshSnapshot(db.collection("directory").document(cleanHandle), 1200L) }
                uTask.await() to dTask.await()
            }
            if (usernameSnap != null && usernameSnap.exists() && usernameSnap.getString("status") != "active") {
                return Result.success(null)
            }
            if (snap == null || !snap.exists()) return Result.success(null)
            val ownerUid = snap.getString("ownerUid").orEmpty()
            if (ownerUid.isBlank()) return Result.success(null)
            if (usernameSnap != null && usernameSnap.exists() &&
                (usernameSnap.getString("ownerUid").orEmpty() != ownerUid ||
                    !usernameSnap.getString("username").orEmpty().equals(cleanHandle, ignoreCase = true))
            ) {
                return Result.success(null)
            }
            val storedHandle = snap.getString("username").orEmpty().ifBlank { cleanHandle }
            if (!storedHandle.equals(cleanHandle, ignoreCase = true)) return Result.success(null)
            val pubSnap = getFreshSnapshot(db.collection("public_profiles").document(ownerUid), 900L)
                ?.takeIf { it.exists() }
            val primarySnap = pickFresherDoc(pubSnap, snap) ?: snap
            val secondarySnap = if (primarySnap == pubSnap) snap else pubSnap
            val effectiveAccountStatus = if (
                primarySnap.getString("accountStatus") == "banned" ||
                primarySnap.getBoolean("bannedByAdmin") == true ||
                snap.getString("accountStatus") == "banned" ||
                snap.getBoolean("bannedByAdmin") == true
            ) "banned" else "active"
            val displayName = primarySnap.getString("displayName").orEmpty()
                .ifBlank { snap.getString("displayName").orEmpty() }
                .ifBlank { storedHandle }
            val visibility = primarySnap.getString("profileVisibility")
                ?: snap.getString("profileVisibility")
            @Suppress("UNCHECKED_CAST")
            val allowedViewers = (primarySnap.get("allowedViewerUids") as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()
            val maxViewers = primarySnap.getLong("maxProfileViewers") ?: 0L
            val visible = canViewerSeeProfile(
                profileVisibility = visibility,
                allowedViewerUids = allowedViewers,
                maxProfileViewers = maxViewers,
                ownerUid = ownerUid,
                viewerUid = viewerUid
            )
            val bio = if (visible) {
                primarySnap.getString("bio").orEmpty().ifBlank { secondarySnap?.getString("bio").orEmpty() }
            } else ""
            val photoData = if (visible) {
                primarySnap.getString("photoData")?.takeIf { it.isNotBlank() }
                    ?: secondarySnap?.getString("photoData")?.takeIf { it.isNotBlank() }
            } else null
            val bannerData = if (visible) {
                primarySnap.getString("bannerData")?.takeIf { it.isNotBlank() }
                    ?: secondarySnap?.getString("bannerData")?.takeIf { it.isNotBlank() }
            } else null
            val verified = effectiveAccountStatus != "banned" &&
                ((primarySnap.getBoolean("verified") == true) ||
                    (secondarySnap?.getBoolean("verified") == true))
            Result.success(
                DirectoryLookupResult(
                    username = storedHandle,
                    displayName = displayName,
                    ownerUid = ownerUid,
                    bio = bio,
                    photoData = photoData,
                    bannerData = bannerData,
                    verified = verified,
                    accountStatus = effectiveAccountStatus
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
            var peerAccountStatus = "active"
            val finalName: String

            if (validType == "chat") {
                cleanHandle = exactUsernameOrNull(username)
                    ?: return Result.failure(IllegalStateException("Account Not Found"))
                val (usernameSnap, dirSnap) = coroutineScope {
                    val uTask = async { getFreshSnapshot(db.collection("usernames").document(cleanHandle), 1200L) }
                    val dTask = async { getFreshSnapshot(db.collection("directory").document(cleanHandle), 1200L) }
                    uTask.await() to dTask.await()
                }
                if (usernameSnap != null && usernameSnap.exists() && usernameSnap.getString("status") != "active") {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                if (dirSnap == null || !dirSnap.exists()) {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                val owner = dirSnap.getString("ownerUid").orEmpty()
                val storedHandle = dirSnap.getString("username").orEmpty()
                if (owner.isBlank() || !storedHandle.equals(cleanHandle, ignoreCase = true)) {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                if (usernameSnap != null && usernameSnap.exists() &&
                    (usernameSnap.getString("ownerUid").orEmpty() != owner ||
                        !usernameSnap.getString("username").orEmpty().equals(cleanHandle, ignoreCase = true))
                ) {
                    return Result.failure(IllegalStateException("Account Not Found"))
                }
                if (owner == uid) {
                    return Result.failure(IllegalStateException("You cannot add your own username."))
                }
                val contactsRef = db.collection("users").document(uid).collection("contacts")
                val (pubSnap, existingByUsername) = coroutineScope {
                    val pubTask = async {
                        getFreshSnapshot(db.collection("public_profiles").document(owner), 800L)
                            ?.takeIf { it.exists() }
                    }
                    val existingTask = async {
                        runCatching {
                            contactsRef.whereEqualTo("username", cleanHandle).limit(1).get(Source.CACHE).await()
                                .documents.firstOrNull()
                        }.getOrNull() ?: withTimeoutOrNull(800L) {
                            runCatching {
                                contactsRef.whereEqualTo("username", cleanHandle).limit(1).get().await()
                                    .documents.firstOrNull()
                            }.getOrNull()
                        }
                    }
                    pubTask.await() to existingTask.await()
                }
                val primarySnap = pickFresherDoc(pubSnap, dirSnap) ?: dirSnap
                val secondarySnap = if (primarySnap == pubSnap) dirSnap else pubSnap
                peerAccountStatus = if (
                    primarySnap.getString("accountStatus") == "banned" ||
                    primarySnap.getBoolean("bannedByAdmin") == true ||
                    dirSnap.getString("accountStatus") == "banned" ||
                    dirSnap.getBoolean("bannedByAdmin") == true
                ) "banned" else "active"
                resolvedPeerUid = owner
                resolvedIsPeer = true
                resolvedDirName = primarySnap.getString("displayName").orEmpty()
                    .ifBlank { dirSnap.getString("displayName").orEmpty() }
                @Suppress("UNCHECKED_CAST")
                val allowedViewers = (primarySnap.get("allowedViewerUids") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?: emptyList()
                val visible = canViewerSeeProfile(
                    profileVisibility = primarySnap.getString("profileVisibility")
                        ?: dirSnap.getString("profileVisibility"),
                    allowedViewerUids = allowedViewers,
                    maxProfileViewers = primarySnap.getLong("maxProfileViewers") ?: 0L,
                    ownerUid = owner,
                    viewerUid = uid
                )
                resolvedBio = if (visible) {
                    primarySnap.getString("bio").orEmpty()
                        .ifBlank { secondarySnap?.getString("bio").orEmpty() }
                        .take(200)
                } else ""
                resolvedPhotoData = if (visible) {
                    primarySnap.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                        ?: secondarySnap?.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                } else null
                resolvedBannerData = if (visible) {
                    primarySnap.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                        ?: secondarySnap?.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                } else null
                val updatedTs = primarySnap.getTimestamp("updatedAt")?.toDate()?.time
                val fresh = updatedTs == null || (System.currentTimeMillis() - updatedTs) <= PRESENCE_STALE_MS
                val showOnline = primarySnap.getBoolean("showOnlineStatus") ?: true
                resolvedOnline = peerAccountStatus != "banned" && showOnline && (primarySnap.getBoolean("online") == true) && fresh
                finalName = resolvedDirName.trim().ifBlank { storedHandle }.take(60)
                val cleanPhone = phone.trim().ifBlank { "@$cleanHandle" }.take(30)

                val existingDoc = existingByUsername ?: runCatching {
                    contactsRef.whereEqualTo("peerUid", resolvedPeerUid).limit(1).get(Source.CACHE).await()
                        .documents.firstOrNull()
                }.getOrNull()
                if (existingDoc != null) {
                    val existingUpdates = mutableMapOf<String, Any>(
                        "username" to cleanHandle,
                        "phone" to cleanPhone,
                        "peerUid" to resolvedPeerUid,
                        "isRegisteredPeer" to resolvedIsPeer,
                        "bio" to resolvedBio,
                        "online" to (resolvedOnline && peerAccountStatus != "banned"),
                        "accountStatus" to peerAccountStatus,
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
                    commitWithFastQueue(existingDoc.reference.update(existingUpdates), maxWaitMs = 1200L)
                    return Result.success(existingDoc.id)
                }
            } else {
                cleanHandle = sanitizeHandle(rawHandle.ifBlank { name })
                val cleanGroupName = name.trim().ifBlank { cleanHandle }.take(60)
                require(cleanGroupName.isNotEmpty()) { "Group name cannot be empty." }
                finalName = cleanGroupName
            }
            val cleanPhone = phone.trim().ifBlank { "@$cleanHandle" }.take(30)

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
                "accountStatus" to peerAccountStatus,
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
            commitWithFastQueue(contactRef.set(contactPayload), maxWaitMs = 1200L)
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

            val contactSnap = runCatching { contactRef.get(Source.CACHE).await() }.getOrNull()
                ?.takeIf { it.exists() }
                ?: withTimeoutOrNull(2500L) {
                    runCatching { contactRef.get().await() }.getOrNull()
                }
            var peerUid = contactSnap?.getString("peerUid").orEmpty()
            val peerUsername = contactSnap?.getString("username").orEmpty()
            val threadType = contactSnap?.getString("threadType").orEmpty()
            val disappearingSec = (contactSnap?.getLong("disappearingSeconds") ?: 0L).coerceAtLeast(0L)
            val expiresAtMs = if (disappearingSec > 0L) {
                System.currentTimeMillis() + disappearingSec * 1000L
            } else {
                0L
            }
            var resolvedPeerName: String? = null
            var linkedNewly = false

            if (validSender == "me" && threadType != "group" && peerUid.isBlank()) {
                if (peerUsername.isNotBlank()) {
                    val cleanPeerHandle = sanitizeHandle(peerUsername)
                    val dirRef = db.collection("directory").document(cleanPeerHandle)
                    val dirSnap = runCatching { dirRef.get(Source.CACHE).await() }.getOrNull()
                        ?.takeIf { it.exists() }
                        ?: withTimeoutOrNull(3500L) {
                            runCatching { dirRef.get().await() }.getOrNull()
                        }
                    if (dirSnap != null && dirSnap.exists() && dirSnap.getString("accountStatus") != "banned") {
                        val owner = dirSnap.getString("ownerUid").orEmpty()
                        if (owner.isNotBlank() && owner != uid) {
                            peerUid = owner
                            resolvedPeerName = dirSnap.getString("displayName")?.takeIf { it.isNotBlank() }?.take(60)
                            linkedNewly = true
                        }
                    }
                }
                if (peerUid.isBlank()) {
                    return Result.failure(
                        IllegalStateException("Recipient account could not be verified. Please refresh or re-add the user.")
                    )
                }
            }

            val initialReceiptStatus = MessageReceiptStatus.SENT.key
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
                "receiptStatus" to initialReceiptStatus,
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
                "lastMessageReceiptStatus" to initialReceiptStatus,
                "lastMessageId" to msgRef.id,
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
                val myProfileSnap = runCatching { userRef.get(Source.CACHE).await() }.getOrNull()
                    ?.takeIf { it.exists() }
                    ?: withTimeoutOrNull(2000L) {
                        runCatching { userRef.get().await() }.getOrNull()
                    }
                val myUsername = myProfileSnap?.getString("username").orEmpty().ifBlank { "nomiuser" }.take(40)
                val myDisplayName = myProfileSnap?.getString("displayName").orEmpty()
                    .ifBlank { auth.currentUser?.displayName.orEmpty() }
                    .ifBlank { sessionUsername.orEmpty() }
                    .ifBlank { "Nomi User" }
                    .take(60)
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
                    "senderMessageId" to msgRef.id,
                    "senderContactId" to contactId,
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
        val uid = activeUserIdOrNull() ?: return
        if (uid != userId) return
        val processedDocIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val processedReceiptIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val retrySignals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val receiptRetrySignals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        suspend fun processRelayDocument(doc: DocumentSnapshot): Boolean {
            if (!processedDocIds.add(doc.id)) {
                return true
            }
            if (processedDocIds.size > 500) {
                synchronized(processedDocIds) {
                    val iter = processedDocIds.iterator()
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
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
            val senderMessageId = doc.getString("senderMessageId")?.takeIf { isValidFirestoreId(it) }
            val senderContactId = doc.getString("senderContactId")?.takeIf { isValidFirestoreId(it) }
            if (fromUid.isBlank() || text.isBlank()) {
                runCatching { doc.reference.delete().await() }
                return true
            }

            val delivered = runCatching {
                val userRef = db.collection("users").document(uid)
                val contactsCol = userRef.collection("contacts")
                val existingByUid = runCatching {
                    contactsCol.whereEqualTo("peerUid", fromUid).limit(1).get(Source.CACHE).await()
                }.getOrNull()?.documents?.firstOrNull()
                    ?: contactsCol.whereEqualTo("peerUid", fromUid).limit(1).get().await().documents.firstOrNull()
                val existingDoc = existingByUid ?: runCatching {
                    contactsCol.whereEqualTo("username", fromUsername).limit(1).get(Source.CACHE).await()
                }.getOrNull()?.documents?.firstOrNull()
                    ?: contactsCol.whereEqualTo("username", fromUsername).limit(1).get().await().documents.firstOrNull()

                if (existingDoc != null && existingDoc.getBoolean("blocked") == true) {
                    doc.reference.delete().await()
                    return@runCatching true
                }

                val peerPubRef = db.collection("public_profiles").document(fromUid)
                val peerPubSnap = getFreshSnapshot(peerPubRef, 2500L)?.takeIf { it.exists() }
                val peerDirDirectSnap = if (fromUsername.isNotBlank()) {
                    getFreshSnapshot(db.collection("directory").document(fromUsername), 2000L)
                        ?.takeIf { it.exists() }
                } else null
                val peerDirSnap = pickFresherDoc(peerPubSnap, peerDirDirectSnap)
                val peerFallbackSnap = if (peerDirSnap == peerPubSnap) peerDirDirectSnap else peerPubSnap
                val livePeerHandle = peerDirSnap?.getString("username")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sanitizeHandle(it) }
                    ?: fromUsername
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
                    peerDirSnap?.getString("bio").orEmpty()
                        .ifBlank { peerFallbackSnap?.getString("bio").orEmpty() }
                        .take(200)
                } else ""
                val peerPhotoData = if (visibleToMe) {
                    peerDirSnap?.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                        ?: peerFallbackSnap?.getString("photoData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                } else null
                val peerBannerData = if (visibleToMe) {
                    peerDirSnap?.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                        ?: peerFallbackSnap?.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }
                } else null

                val batch = db.batch()
                val targetContactId: String
                var isBlockedContact = false
                var isMutedContact = false
                val ackStatus: String
                if (existingDoc == null) {
                    val newContactRef = contactsCol.document()
                    targetContactId = newContactRef.id
                    val msgRef = if (isValidFirestoreId(doc.id)) {
                        newContactRef.collection("messages").document(doc.id)
                    } else {
                        newContactRef.collection("messages").document()
                    }
                    val isActiveOpen = activeContactIdProvider() == targetContactId
                    ackStatus = if (isActiveOpen) MessageReceiptStatus.READ.key else MessageReceiptStatus.DELIVERED.key
                    val newContactPayload = mutableMapOf<String, Any>(
                        "userId" to uid,
                        "name" to livePeerName,
                        "username" to livePeerHandle,
                        "phone" to "@$livePeerHandle",
                        "threadType" to "chat",
                        "bio" to peerBio,
                        "online" to false,
                        "typing" to false,
                        "unreadCount" to if (isActiveOpen) 0L else 1L,
                        "lastMessageText" to text.take(1000),
                        "lastMessageFromMe" to false,
                        "lastMessageTimeLabel" to timeLabel,
                        "lastMessageReceiptStatus" to ackStatus,
                        "lastMessageId" to msgRef.id,
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
                        "receiptStatus" to ackStatus,
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
                    if (senderMessageId != null) {
                        msgPayload["senderMessageId"] = senderMessageId
                    }
                    if (senderContactId != null) {
                        msgPayload["senderContactId"] = senderContactId
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
                    val msgRef = if (isValidFirestoreId(doc.id)) {
                        contactRef.collection("messages").document(doc.id)
                    } else {
                        contactRef.collection("messages").document()
                    }
                    val isActiveOpen = activeContactIdProvider() == targetContactId
                    ackStatus = if (isActiveOpen) MessageReceiptStatus.READ.key else MessageReceiptStatus.DELIVERED.key
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
                        "receiptStatus" to ackStatus,
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
                    if (senderMessageId != null) {
                        msgPayload["senderMessageId"] = senderMessageId
                    }
                    if (senderContactId != null) {
                        msgPayload["senderContactId"] = senderContactId
                    }
                    batch.set(msgRef, msgPayload)
                    val existingContactUpdates = mutableMapOf<String, Any>(
                        "username" to livePeerHandle,
                        "peerUid" to fromUid,
                        "isRegisteredPeer" to true,
                        "bio" to peerBio,
                        "lastMessageText" to text.take(1000),
                        "lastMessageFromMe" to false,
                        "lastMessageTimeLabel" to timeLabel,
                        "lastMessageReceiptStatus" to ackStatus,
                        "lastMessageId" to msgRef.id,
                        "unreadCount" to if (isActiveOpen) 0L else FieldValue.increment(1L),
                        "typing" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    val oldPhone = existingDoc.getString("phone").orEmpty()
                    if (oldPhone.isBlank() || oldPhone.startsWith("@")) {
                        existingContactUpdates["phone"] = "@$livePeerHandle"
                    }
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

                if (!isBlockedContact && senderMessageId != null && senderContactId != null && fromUid != uid) {
                    val receiptRef = db.collection("message_receipts").document()
                    batch.set(
                        receiptRef,
                        mapOf(
                            "fromUid" to uid,
                            "toUid" to fromUid,
                            "senderContactId" to senderContactId,
                            "senderMessageId" to senderMessageId,
                            "status" to ackStatus,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
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
            }.onFailure { err ->
                if (err is CancellationException) throw err
                processedDocIds.remove(doc.id)
            }
            return delivered.getOrDefault(false)
        }

        suspend fun processReceiptDocument(receiptDoc: DocumentSnapshot): Boolean {
            if (!processedReceiptIds.add(receiptDoc.id)) {
                return true
            }
            if (processedReceiptIds.size > 500) {
                synchronized(processedReceiptIds) {
                    val iter = processedReceiptIds.iterator()
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }

            val senderContactId = receiptDoc.getString("senderContactId").orEmpty()
            val senderMessageId = receiptDoc.getString("senderMessageId").orEmpty()
            val incomingStatus = receiptDoc.getString("status").orEmpty()
            if (!isValidFirestoreId(senderContactId) ||
                !isValidFirestoreId(senderMessageId) ||
                incomingStatus !in listOf(MessageReceiptStatus.DELIVERED.key, MessageReceiptStatus.READ.key)
            ) {
                runCatching { receiptDoc.reference.delete().await() }
                return true
            }

            val applied = runCatching {
                val contactRef = db.collection("users").document(uid)
                    .collection("contacts").document(senderContactId)
                val msgRef = contactRef.collection("messages").document(senderMessageId)

                val (contactSnap, msgSnap) = coroutineScope {
                    val cTask = async {
                        runCatching { contactRef.get(Source.CACHE).await() }.getOrNull()
                            ?.takeIf { it.exists() }
                            ?: withTimeoutOrNull(2000L) { runCatching { contactRef.get().await() }.getOrNull() }
                    }
                    val mTask = async {
                        runCatching { msgRef.get(Source.CACHE).await() }.getOrNull()
                            ?.takeIf { it.exists() }
                            ?: withTimeoutOrNull(2000L) { runCatching { msgRef.get().await() }.getOrNull() }
                    }
                    cTask.await() to mTask.await()
                }

                val batch = db.batch()
                if (msgSnap != null && msgSnap.exists()) {
                    val currentStatus = msgSnap.getString("receiptStatus")
                    val nextStatus = MessageReceiptStatus.maxStatus(currentStatus, incomingStatus)
                    if (nextStatus != currentStatus) {
                        batch.update(msgRef, "receiptStatus", nextStatus)
                    }
                    if (contactSnap != null && contactSnap.exists()) {
                        val lastMsgId = contactSnap.getString("lastMessageId").orEmpty()
                        val matchesLatest = lastMsgId == senderMessageId ||
                            (lastMsgId.isBlank() && contactSnap.getBoolean("lastMessageFromMe") == true)
                        if (matchesLatest) {
                            val currentThreadStatus = contactSnap.getString("lastMessageReceiptStatus")
                            val nextThreadStatus = MessageReceiptStatus.maxStatus(currentThreadStatus, nextStatus)
                            if (nextThreadStatus != currentThreadStatus) {
                                batch.update(
                                    contactRef,
                                    mapOf(
                                        "lastMessageReceiptStatus" to nextThreadStatus,
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                )
                            }
                        }
                    }
                }
                batch.delete(receiptDoc.reference)
                batch.commit().await()
                true
            }.onFailure { err ->
                if (err is CancellationException) throw err
                processedReceiptIds.remove(receiptDoc.id)
            }
            return applied.getOrDefault(false)
        }

        coroutineScope {
            // Retry sweep for any failed or missed direct_messages documents
            launch {
                retrySignals.collectLatest {
                    var backoffMs = 1500L
                    while (isActive && activeUserIdOrNull() == uid) {
                        delay(backoffMs)
                        val pendingDocs = runCatching {
                            db.collection("direct_messages")
                                .whereEqualTo("toUid", uid)
                                .get(Source.SERVER)
                                .await()
                                .documents
                        }.getOrNull() ?: break
                        if (pendingDocs.isEmpty()) break
                        var anyFailed = false
                        for (doc in pendingDocs) {
                            if (!processRelayDocument(doc)) {
                                anyFailed = true
                            }
                        }
                        if (!anyFailed) break
                        backoffMs = minOf(12_000L, backoffMs * 2L)
                    }
                }
            }

            // Initial server catch-up sweep in case relay docs arrived while offline
            launch {
                val catchUpDocs = runCatching {
                    db.collection("direct_messages")
                        .whereEqualTo("toUid", uid)
                        .get(Source.SERVER)
                        .await()
                        .documents
                }.getOrDefault(emptyList())
                var anyFailed = false
                for (doc in catchUpDocs) {
                    if (!processRelayDocument(doc)) {
                        anyFailed = true
                    }
                }
                if (anyFailed) {
                    retrySignals.tryEmit(Unit)
                }
            }

            // Retry & catch-up sweep for message_receipts
            launch {
                receiptRetrySignals.collectLatest {
                    var backoffMs = 1500L
                    while (isActive && activeUserIdOrNull() == uid) {
                        delay(backoffMs)
                        val pendingReceipts = runCatching {
                            db.collection("message_receipts")
                                .whereEqualTo("toUid", uid)
                                .get(Source.SERVER)
                                .await()
                                .documents
                        }.getOrNull() ?: break
                        if (pendingReceipts.isEmpty()) break
                        var anyFailed = false
                        for (doc in pendingReceipts) {
                            if (!processReceiptDocument(doc)) {
                                anyFailed = true
                            }
                        }
                        if (!anyFailed) break
                        backoffMs = minOf(12_000L, backoffMs * 2L)
                    }
                }
            }

            launch {
                val catchUpReceipts = runCatching {
                    db.collection("message_receipts")
                        .whereEqualTo("toUid", uid)
                        .get(Source.SERVER)
                        .await()
                        .documents
                }.getOrDefault(emptyList())
                var anyFailed = false
                for (doc in catchUpReceipts) {
                    if (!processReceiptDocument(doc)) {
                        anyFailed = true
                    }
                }
                if (anyFailed) {
                    receiptRetrySignals.tryEmit(Unit)
                }
            }

            launch {
                db.collection("message_receipts")
                    .whereEqualTo("toUid", uid)
                    .snapshots()
                    .retryWhen { error, attempt ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        if (firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            false
                        } else {
                            delay(minOf(15_000L, 1_000L * (attempt + 1)))
                            activeUserIdOrNull() == uid
                        }
                    }
                    .collect { snapshot ->
                        var anyFailed = false
                        for (doc in snapshot.documents) {
                            if (!processReceiptDocument(doc)) {
                                anyFailed = true
                            }
                        }
                        if (anyFailed) {
                            receiptRetrySignals.tryEmit(Unit)
                        }
                    }
            }

            db.collection("direct_messages")
                .whereEqualTo("toUid", uid)
                .snapshots()
                .retryWhen { error, attempt ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && activeUserIdOrNull() == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, "direct_messages")
                    }
                    if (firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        false
                    } else {
                        delay(minOf(15_000L, 1_000L * (attempt + 1)))
                        activeUserIdOrNull() == uid
                    }
                }
                .collect { snapshot ->
                    var anyFailed = false
                    for (doc in snapshot.documents) {
                        if (!processRelayDocument(doc)) {
                            anyFailed = true
                        }
                    }
                    if (anyFailed) {
                        retrySignals.tryEmit(Unit)
                    }
                }
        }
    }

    override fun queueOfflinePresence(userId: String) {
        val uid = activeUserIdOrNull() ?: return
        if (uid != userId) return
        runCatching {
            val payload = mapOf(
                "online" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(uid).update(payload)
            db.collection("public_profiles").document(uid).update(payload)
        }
    }

    override suspend fun setUserPresence(userId: String, online: Boolean) {
        val uid = activeUserIdOrNull() ?: return
        if (uid != userId) return
        runCatching {
            val userRef = db.collection("users").document(uid)
            val snap = runCatching { userRef.get(Source.CACHE).await() }.getOrNull()
                ?.takeIf { it.exists() }
                ?: userRef.get().await()
            if (!snap.exists()) return
            val isVerified = (snap.getBoolean("verified") == true) || isVerifiedAdminSession()
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
                val dirSnap = runCatching { dirRef.get(Source.CACHE).await() }.getOrNull()
                    ?.takeIf { it.exists() }
                    ?: dirRef.get().await()
                if (dirSnap.exists() && dirSnap.getString("ownerUid") == uid) {
                    val dirPresence = mutableMapOf<String, Any>(
                        "online" to effectiveOnline,
                        "showOnlineStatus" to showOnline,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (isVerified && dirSnap.getBoolean("verified") != true) {
                        dirPresence["verified"] = true
                    }
                    dirRef.update(dirPresence).await()
                }
            }
            val pubRef = db.collection("public_profiles").document(uid)
            val pubSnap = runCatching { pubRef.get(Source.CACHE).await() }.getOrNull()
                ?.takeIf { it.exists() }
                ?: runCatching { pubRef.get().await() }.getOrNull()
            if (pubSnap != null && pubSnap.exists()) {
                val pubPresence = mutableMapOf<String, Any>(
                    "online" to effectiveOnline,
                    "showOnlineStatus" to showOnline,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (isVerified && pubSnap.getBoolean("verified") != true) {
                    pubPresence["verified"] = true
                }
                pubRef.update(pubPresence).await()
            }
        }
    }

    override suspend fun markThreadRead(userId: String, contactId: String): Result<Unit> {
        val path = "users/$userId/contacts/$contactId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(contactId.isNotBlank()) { "contactId cannot be blank." }
            val contactRef = db.collection("users").document(uid)
                .collection("contacts").document(contactId)
            val contactSnap = runCatching { contactRef.get(Source.CACHE).await() }.getOrNull()
                ?.takeIf { it.exists() }
                ?: runCatching { contactRef.get().await() }.getOrNull()
            val peerUid = contactSnap?.getString("peerUid").orEmpty().trim()
            val threadType = contactSnap?.getString("threadType").orEmpty()
            val canAckRemote = peerUid.isNotBlank() && peerUid != uid && threadType != "group"

            val incomingMsgDocs = runCatching {
                contactRef.collection("messages")
                    .whereEqualTo("sender", "them")
                    .get()
                    .await()
                    .documents
            }.getOrDefault(emptyList())

            val unreadIncoming = incomingMsgDocs.filter { doc ->
                doc.getString("receiptStatus") != MessageReceiptStatus.READ.key
            }

            if (unreadIncoming.isEmpty()) {
                contactRef.update(
                    mapOf(
                        "unreadCount" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val ops = mutableListOf<(com.google.firebase.firestore.WriteBatch) -> Unit>()
                ops.add { b ->
                    b.update(
                        contactRef,
                        mapOf(
                            "unreadCount" to 0L,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                for (msgDoc in unreadIncoming) {
                    ops.add { b -> b.update(msgDoc.reference, "receiptStatus", MessageReceiptStatus.READ.key) }
                    val sMsgId = msgDoc.getString("senderMessageId").orEmpty()
                    val sContactId = msgDoc.getString("senderContactId").orEmpty()
                    if (canAckRemote && isValidFirestoreId(sMsgId) && isValidFirestoreId(sContactId)) {
                        val receiptRef = db.collection("message_receipts").document()
                        ops.add { b ->
                            b.set(
                                receiptRef,
                                mapOf(
                                    "fromUid" to uid,
                                    "toUid" to peerUid,
                                    "senderContactId" to sContactId,
                                    "senderMessageId" to sMsgId,
                                    "status" to MessageReceiptStatus.READ.key,
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                    }
                }
                for (chunk in ops.chunked(380)) {
                    val batch = db.batch()
                    chunk.forEach { it(batch) }
                    batch.commit().await()
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
                    "lastMessageReceiptStatus" to MessageReceiptStatus.SENT.key,
                    "lastMessageId" to "",
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
        reporterUsername: String,
        reason: String
    ): Result<ReportOutcome> {
        val contactPath = "users/$userId/contacts/${contact.id}"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val reporterKey = sanitizeHandle(reporterUsername.ifBlank { uid })
            val cleanReason = reason.trim().ifBlank { "Other" }.take(100)

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
            var resolvedReportedUid = contact.peerUid

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
                resolvedReportedUid = resolvedReportedUid.ifBlank {
                    dirSnap.getString("ownerUid").orEmpty()
                }
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

            // Create an admin-only moderation alert in /moderation_alerts
            val alertRef = db.collection("moderation_alerts").document()
            val alertPayload = mutableMapOf<String, Any>(
                "reportedUid" to resolvedReportedUid.take(128),
                "reportedUsername" to targetHandle,
                "reportedDisplayName" to contact.name.ifBlank { targetHandle }.take(60),
                "reporterUid" to uid,
                "reporterUsername" to reporterKey,
                "reason" to cleanReason,
                "status" to "pending",
                "createdAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            auth.currentUser?.email?.trim()?.takeIf { it.isNotBlank() }?.let { email ->
                alertPayload["reporterEmail"] = email.take(128)
            }
            runCatching { alertRef.set(alertPayload).await() }

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

    override suspend fun submitBanAppeal(
        userId: String,
        username: String,
        displayName: String,
        appealMessage: String,
        aiRecommendation: String,
        aiRiskLevel: String,
        aiAnalysis: String,
        aiPrompt: String
    ): Result<Unit> {
        val alertRef = db.collection("moderation_alerts").document()
        val docPath = "moderation_alerts/${alertRef.id}"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val cleanAppeal = appealMessage.trim().take(500)
            require(cleanAppeal.isNotBlank()) { "Appeal message cannot be blank." }

            runCatching { db.enableNetwork().await() }
            val userSnap = runCatching { db.collection("users").document(uid).get().await() }.getOrNull()
            val resolvedHandle = sanitizeHandle(
                username.ifBlank { userSnap?.getString("username").orEmpty() }
            )
            val resolvedName = displayName.trim()
                .ifBlank { userSnap?.getString("displayName").orEmpty() }
                .ifBlank { resolvedHandle }
                .take(60)
            val normalizedRecommendation = if (aiRecommendation.equals("RESTORE", ignoreCase = true)) {
                "RESTORE"
            } else {
                "KEEP_BANNED"
            }
            val normalizedRisk = when (aiRiskLevel.uppercase()) {
                "LOW", "MEDIUM", "HIGH" -> aiRiskLevel.uppercase()
                else -> "MEDIUM"
            }

            val payload = mutableMapOf<String, Any>(
                "alertType" to "appeal",
                "reportedUid" to uid.take(128),
                "reportedUsername" to resolvedHandle,
                "reportedDisplayName" to resolvedName,
                "reporterUid" to uid.take(128),
                "reporterUsername" to resolvedHandle,
                "reason" to "Ban Appeal",
                "appealMessage" to cleanAppeal,
                "aiRecommendation" to normalizedRecommendation,
                "aiRiskLevel" to normalizedRisk,
                "aiAnalysis" to aiAnalysis.trim().take(350),
                "aiPrompt" to aiPrompt.trim().take(250),
                "status" to "pending",
                "createdAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            auth.currentUser?.email?.trim()?.takeIf { it.isNotBlank() }?.let { email ->
                payload["reporterEmail"] = email.take(128)
            }
            alertRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun ensureAdminConfigLocked(uid: String, email: String): Result<Unit> {
        val docPath = "admin_config/primary"
        return try {
            val adminEmail = requireVerifiedAdmin()
            check(auth.currentUser?.uid == uid) { "Admin UID mismatch." }
            check(email.equals(adminEmail, ignoreCase = true)) { "Admin Gmail lock mismatch." }
            val ref = db.collection("admin_config").document("primary")
            val snap = ref.get().await()
            if (!snap.exists()) {
                ref.set(
                    mapOf(
                        "lockedGmail" to ADMIN_GMAIL,
                        "adminUid" to uid,
                        "enabled" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.update(
                    mapOf(
                        "lockedGmail" to ADMIN_GMAIL,
                        "adminUid" to uid,
                        "enabled" to true,
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

    override fun observeModerationAlerts(): Flow<List<ModerationAlert>> = flow {
        if (!isVerifiedAdminSession()) {
            emit(emptyList())
            return@flow
        }
        val path = "moderation_alerts"
        emitAll(
            db.collection("moderation_alerts")
                .orderBy("createdAtMs", Query.Direction.DESCENDING)
                .limit(100)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ModerationAlert::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && isVerifiedAdminSession()) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    if (firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        emit(emptyList())
                    } else {
                        throw error
                    }
                }
        )
    }

    override suspend fun ignoreModerationAlert(alertId: String): Result<Unit> {
        val docPath = "moderation_alerts/$alertId"
        return try {
            requireVerifiedAdmin()
            require(alertId.isNotBlank()) { "Invalid alert ID." }
            runCatching { db.enableNetwork().await() }
            db.collection("moderation_alerts").document(alertId)
                .update(
                    mapOf(
                        "status" to "ignored",
                        "adminEmail" to ADMIN_GMAIL,
                        "resolvedAtMs" to System.currentTimeMillis(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun banUserFromAlert(alert: ModerationAlert): Result<Unit> {
        val docPath = "moderation_alerts/${alert.id}"
        return try {
            requireVerifiedAdmin()
            runCatching { db.enableNetwork().await() }
            var targetHandle = sanitizeHandle(alert.reportedUsername)
            var targetUid = alert.reportedUid.trim()

            if (targetUid.isBlank() && targetHandle.isNotBlank()) {
                val dirSnap = getFreshSnapshot(db.collection("directory").document(targetHandle), 2500L)
                    ?: runCatching { db.collection("directory").document(targetHandle).get().await() }.getOrNull()
                targetUid = dirSnap?.getString("ownerUid").orEmpty().trim()
            }
            if (targetUid.isNotBlank() && targetHandle.isBlank()) {
                val userSnap = getFreshSnapshot(db.collection("users").document(targetUid), 2500L)
                    ?: runCatching { db.collection("users").document(targetUid).get().await() }.getOrNull()
                targetHandle = sanitizeHandle(userSnap?.getString("username").orEmpty())
            }

            val batch = db.batch()
            val nowTime = FieldValue.serverTimestamp()
            val resolvedNowMs = System.currentTimeMillis()
            val updatedAlertIds = linkedSetOf<String>()

            if (alert.id.isNotBlank()) {
                updatedAlertIds.add(alert.id)
                batch.update(
                    db.collection("moderation_alerts").document(alert.id),
                    mapOf(
                        "status" to "banned",
                        "adminEmail" to ADMIN_GMAIL,
                        "resolvedAtMs" to resolvedNowMs,
                        "updatedAt" to nowTime
                    )
                )
            }
            if (targetUid.isNotBlank()) {
                val userRef = db.collection("users").document(targetUid)
                val userSnap = getFreshSnapshot(userRef, 2500L)
                    ?: runCatching { userRef.get().await() }.getOrNull()
                if (userSnap != null && userSnap.exists()) {
                    if (targetHandle.isBlank()) {
                        targetHandle = sanitizeHandle(userSnap.getString("username").orEmpty())
                    }
                    batch.update(
                        userRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
                val pubRef = db.collection("public_profiles").document(targetUid)
                val pubSnap = getFreshSnapshot(pubRef, 2500L)
                    ?: runCatching { pubRef.get().await() }.getOrNull()
                if (pubSnap != null && pubSnap.exists()) {
                    batch.update(
                        pubRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }
            if (targetHandle.isNotBlank()) {
                val dirRef = db.collection("directory").document(targetHandle)
                val dirSnap = getFreshSnapshot(dirRef, 2500L)
                    ?: runCatching { dirRef.get().await() }.getOrNull()
                if (dirSnap != null && dirSnap.exists()) {
                    batch.update(
                        dirRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
                val acctRef = db.collection("username_accounts").document(targetHandle)
                val acctSnap = getFreshSnapshot(acctRef, 2500L)
                    ?: runCatching { acctRef.get().await() }.getOrNull()
                if (acctSnap != null && acctSnap.exists()) {
                    batch.update(
                        acctRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }

            if (targetUid.isNotBlank()) {
                val alertsByUid = runCatching {
                    db.collection("moderation_alerts").whereEqualTo("reportedUid", targetUid).get().await()
                }.getOrNull()
                alertsByUid?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "banned",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }
            if (targetHandle.isNotBlank()) {
                val alertsByHandle = runCatching {
                    db.collection("moderation_alerts").whereEqualTo("reportedUsername", targetHandle).get().await()
                }.getOrNull()
                alertsByHandle?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "banned",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun recoverUserFromAlert(alert: ModerationAlert): Result<Unit> {
        val docPath = "moderation_alerts/${alert.id}"
        return try {
            requireVerifiedAdmin()
            runCatching { db.enableNetwork().await() }
            var targetHandle = sanitizeHandle(alert.reportedUsername)
            var targetUid = alert.reportedUid.trim()

            if (targetUid.isBlank() && targetHandle.isNotBlank()) {
                val dirSnap = getFreshSnapshot(db.collection("directory").document(targetHandle), 2500L)
                    ?: runCatching { db.collection("directory").document(targetHandle).get().await() }.getOrNull()
                targetUid = dirSnap?.getString("ownerUid").orEmpty().trim()
            }
            if (targetUid.isNotBlank() && targetHandle.isBlank()) {
                val userSnap = getFreshSnapshot(db.collection("users").document(targetUid), 2500L)
                    ?: runCatching { db.collection("users").document(targetUid).get().await() }.getOrNull()
                targetHandle = sanitizeHandle(userSnap?.getString("username").orEmpty())
            }

            val batch = db.batch()
            val nowTime = FieldValue.serverTimestamp()
            val resolvedNowMs = System.currentTimeMillis()
            val updatedAlertIds = linkedSetOf<String>()

            if (alert.id.isNotBlank()) {
                updatedAlertIds.add(alert.id)
                batch.update(
                    db.collection("moderation_alerts").document(alert.id),
                    mapOf(
                        "status" to "recovered",
                        "adminEmail" to ADMIN_GMAIL,
                        "resolvedAtMs" to resolvedNowMs,
                        "updatedAt" to nowTime
                    )
                )
            }
            if (targetUid.isNotBlank()) {
                val userRef = db.collection("users").document(targetUid)
                val userSnap = getFreshSnapshot(userRef, 2500L)
                    ?: runCatching { userRef.get().await() }.getOrNull()
                if (userSnap != null && userSnap.exists()) {
                    if (targetHandle.isBlank()) {
                        targetHandle = sanitizeHandle(userSnap.getString("username").orEmpty())
                    }
                    batch.update(
                        userRef,
                        mapOf(
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "bannedByAdmin" to false,
                            "updatedAt" to nowTime
                        )
                    )
                }
                val pubRef = db.collection("public_profiles").document(targetUid)
                val pubSnap = getFreshSnapshot(pubRef, 2500L)
                    ?: runCatching { pubRef.get().await() }.getOrNull()
                if (pubSnap != null && pubSnap.exists()) {
                    batch.update(
                        pubRef,
                        mapOf(
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }
            if (targetHandle.isNotBlank()) {
                val dirRef = db.collection("directory").document(targetHandle)
                val dirSnap = getFreshSnapshot(dirRef, 2500L)
                    ?: runCatching { dirRef.get().await() }.getOrNull()
                if (dirSnap != null && dirSnap.exists()) {
                    batch.update(
                        dirRef,
                        mapOf(
                            "accountStatus" to "active",
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "bannedByAdmin" to false,
                            "updatedAt" to nowTime
                        )
                    )
                }
                val acctRef = db.collection("username_accounts").document(targetHandle)
                val acctSnap = getFreshSnapshot(acctRef, 2500L)
                    ?: runCatching { acctRef.get().await() }.getOrNull()
                if (acctSnap != null && acctSnap.exists()) {
                    batch.update(
                        acctRef,
                        mapOf(
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }

            if (targetUid.isNotBlank()) {
                val alertsByUid = runCatching {
                    db.collection("moderation_alerts").whereEqualTo("reportedUid", targetUid).get().await()
                }.getOrNull()
                alertsByUid?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "recovered",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }
            if (targetHandle.isNotBlank()) {
                val alertsByHandle = runCatching {
                    db.collection("moderation_alerts").whereEqualTo("reportedUsername", targetHandle).get().await()
                }.getOrNull()
                alertsByHandle?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "recovered",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override fun observeAdminUsers(): Flow<List<AdminUserRecord>> = channelFlow {
        if (!isVerifiedAdminSession()) {
            trySend(emptyList())
            return@channelFlow
        }
        var usersMap = emptyMap<String, UserProfile>()
        var directoryMap = emptyMap<String, UserDirectoryEntry>()

        fun emitCombined() {
            val allUids = (usersMap.keys + directoryMap.values.mapNotNull {
                it.ownerUid?.trim()?.takeIf { id -> id.isNotBlank() }
            }).distinct()
            val list = allUids.map { uid ->
                val userProfile = usersMap[uid]
                val directoryEntry = directoryMap.values.firstOrNull { it.ownerUid == uid }
                val username = userProfile?.username?.takeIf { it.isNotBlank() }
                    ?: directoryEntry?.username.orEmpty()
                val isBanned = userProfile?.accountStatus == "banned" ||
                    userProfile?.bannedByAdmin == true ||
                    directoryEntry?.accountStatus == "banned" ||
                    directoryEntry?.bannedByAdmin == true
                AdminUserRecord(
                    uid = uid,
                    username = username,
                    displayName = userProfile?.displayName?.takeIf { it.isNotBlank() }
                        ?: directoryEntry?.displayName?.takeIf { it.isNotBlank() }
                        ?: "User",
                    email = userProfile?.email.orEmpty(),
                    bio = userProfile?.bio?.takeIf { it.isNotBlank() }
                        ?: directoryEntry?.bio.orEmpty(),
                    photoData = userProfile?.photoData?.takeIf { it.isNotBlank() }
                        ?: directoryEntry?.photoData?.takeIf { it.isNotBlank() },
                    online = !isBanned && (userProfile?.online == true || directoryEntry?.online == true),
                    verified = userProfile?.verified == true || directoryEntry?.verified == true,
                    accountStatus = if (isBanned) "banned" else "active",
                    reportCount = maxOf(userProfile?.reportCount ?: 0L, directoryEntry?.reportCount ?: 0L),
                    bannedByAdmin = userProfile?.bannedByAdmin == true || directoryEntry?.bannedByAdmin == true,
                    updatedAt = userProfile?.updatedAt ?: directoryEntry?.updatedAt
                )
            }.sortedByDescending { it.updatedAt?.seconds ?: 0L }
            trySend(list)
        }

        val usersJob = launch {
            db.collection("users").snapshots()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && isVerifiedAdminSession()) {
                        handleFirestoreError(toLog, OperationType.LIST, "users")
                    }
                }
                .collect { snapshot ->
                    usersMap = snapshot.documents.associate { doc ->
                        val profile = doc.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: UserProfile()
                        doc.id to profile
                    }
                    emitCombined()
                }
        }

        val directoryJob = launch {
            db.collection("directory").snapshots()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && isVerifiedAdminSession()) {
                        handleFirestoreError(toLog, OperationType.LIST, "directory")
                    }
                }
                .collect { snapshot ->
                    directoryMap = snapshot.documents.associate { doc ->
                        val entry = doc.toObject(
                            UserDirectoryEntry::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: UserDirectoryEntry()
                        doc.id to entry
                    }
                    emitCombined()
                }
        }

        awaitClose {
            usersJob.cancel()
            directoryJob.cancel()
        }
    }

    override suspend fun adminVerifyUser(uid: String, username: String, verified: Boolean): Result<Unit> {
        val docPath = "users/$uid"
        return try {
            requireVerifiedAdmin()
            require(uid.isNotBlank()) { "Invalid user ID." }
            runCatching { db.enableNetwork().await() }
            val batch = db.batch()
            val nowTime = FieldValue.serverTimestamp()

            val userRef = db.collection("users").document(uid)
            val userSnap = getFreshSnapshot(userRef, 2500L)
                ?: runCatching { userRef.get().await() }.getOrNull()
            var cleanHandle = sanitizeHandle(username)
            if (userSnap != null && userSnap.exists()) {
                if (cleanHandle.isBlank()) {
                    cleanHandle = sanitizeHandle(userSnap.getString("username").orEmpty())
                }
                batch.update(userRef, mapOf("verified" to verified, "updatedAt" to nowTime))
            }

            val pubRef = db.collection("public_profiles").document(uid)
            val pubSnap = getFreshSnapshot(pubRef, 2500L)
                ?: runCatching { pubRef.get().await() }.getOrNull()
            if (pubSnap != null && pubSnap.exists()) {
                batch.update(pubRef, mapOf("verified" to verified, "updatedAt" to nowTime))
            }

            if (cleanHandle.isNotBlank()) {
                val dirRef = db.collection("directory").document(cleanHandle)
                val dirSnap = getFreshSnapshot(dirRef, 2500L)
                    ?: runCatching { dirRef.get().await() }.getOrNull()
                if (dirSnap != null && dirSnap.exists()) {
                    batch.update(dirRef, mapOf("verified" to verified, "updatedAt" to nowTime))
                }
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun adminBanUser(uid: String, username: String): Result<Unit> {
        val docPath = "users/$uid"
        return try {
            requireVerifiedAdmin()
            require(uid.isNotBlank() || username.isNotBlank()) { "Invalid target user." }
            runCatching { db.enableNetwork().await() }
            val batch = db.batch()
            val nowTime = FieldValue.serverTimestamp()
            val resolvedNowMs = System.currentTimeMillis()
            var cleanHandle = sanitizeHandle(username)

            if (uid.isNotBlank()) {
                val userRef = db.collection("users").document(uid)
                val userSnap = getFreshSnapshot(userRef, 2500L)
                    ?: runCatching { userRef.get().await() }.getOrNull()
                if (userSnap != null && userSnap.exists()) {
                    if (cleanHandle.isBlank()) {
                        cleanHandle = sanitizeHandle(userSnap.getString("username").orEmpty())
                    }
                    batch.update(
                        userRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }

                val pubRef = db.collection("public_profiles").document(uid)
                val pubSnap = getFreshSnapshot(pubRef, 2500L)
                    ?: runCatching { pubRef.get().await() }.getOrNull()
                if (pubSnap != null && pubSnap.exists()) {
                    batch.update(
                        pubRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }

            if (cleanHandle.isNotBlank()) {
                val dirRef = db.collection("directory").document(cleanHandle)
                val dirSnap = getFreshSnapshot(dirRef, 2500L)
                    ?: runCatching { dirRef.get().await() }.getOrNull()
                if (dirSnap != null && dirSnap.exists()) {
                    batch.update(
                        dirRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
                val acctRef = db.collection("username_accounts").document(cleanHandle)
                val acctSnap = getFreshSnapshot(acctRef, 2500L)
                    ?: runCatching { acctRef.get().await() }.getOrNull()
                if (acctSnap != null && acctSnap.exists()) {
                    batch.update(
                        acctRef,
                        mapOf(
                            "accountStatus" to "banned",
                            "bannedByAdmin" to true,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }

            val updatedAlertIds = linkedSetOf<String>()
            if (uid.isNotBlank()) {
                val alertsSnap = runCatching {
                    db.collection("moderation_alerts")
                        .whereEqualTo("reportedUid", uid)
                        .get()
                        .await()
                }.getOrNull()
                alertsSnap?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "banned",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }
            if (cleanHandle.isNotBlank()) {
                val alertsByHandle = runCatching {
                    db.collection("moderation_alerts")
                        .whereEqualTo("reportedUsername", cleanHandle)
                        .get()
                        .await()
                }.getOrNull()
                alertsByHandle?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "banned",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun adminUnbanUser(uid: String, username: String): Result<Unit> {
        val docPath = "users/$uid"
        return try {
            requireVerifiedAdmin()
            require(uid.isNotBlank() || username.isNotBlank()) { "Invalid target user." }
            runCatching { db.enableNetwork().await() }
            val batch = db.batch()
            val nowTime = FieldValue.serverTimestamp()
            val resolvedNowMs = System.currentTimeMillis()
            var cleanHandle = sanitizeHandle(username)

            if (uid.isNotBlank()) {
                val userRef = db.collection("users").document(uid)
                val userSnap = getFreshSnapshot(userRef, 2500L)
                    ?: runCatching { userRef.get().await() }.getOrNull()
                if (userSnap != null && userSnap.exists()) {
                    if (cleanHandle.isBlank()) {
                        cleanHandle = sanitizeHandle(userSnap.getString("username").orEmpty())
                    }
                    batch.update(
                        userRef,
                        mapOf(
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "reportCount" to 0L,
                            "updatedAt" to nowTime
                        )
                    )
                }

                val pubRef = db.collection("public_profiles").document(uid)
                val pubSnap = getFreshSnapshot(pubRef, 2500L)
                    ?: runCatching { pubRef.get().await() }.getOrNull()
                if (pubSnap != null && pubSnap.exists()) {
                    batch.update(
                        pubRef,
                        mapOf(
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }

            if (cleanHandle.isNotBlank()) {
                val dirRef = db.collection("directory").document(cleanHandle)
                val dirSnap = getFreshSnapshot(dirRef, 2500L)
                    ?: runCatching { dirRef.get().await() }.getOrNull()
                if (dirSnap != null && dirSnap.exists()) {
                    batch.update(
                        dirRef,
                        mapOf(
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "reportCount" to 0L,
                            "reportedByUids" to emptyList<String>(),
                            "updatedAt" to nowTime
                        )
                    )
                }
                val acctRef = db.collection("username_accounts").document(cleanHandle)
                val acctSnap = getFreshSnapshot(acctRef, 2500L)
                    ?: runCatching { acctRef.get().await() }.getOrNull()
                if (acctSnap != null && acctSnap.exists()) {
                    batch.update(
                        acctRef,
                        mapOf(
                            "accountStatus" to "active",
                            "bannedByAdmin" to false,
                            "updatedAt" to nowTime
                        )
                    )
                }
            }

            val updatedAlertIds = linkedSetOf<String>()
            if (uid.isNotBlank()) {
                val alertsSnap = runCatching {
                    db.collection("moderation_alerts")
                        .whereEqualTo("reportedUid", uid)
                        .get()
                        .await()
                }.getOrNull()
                alertsSnap?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "recovered",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }
            if (cleanHandle.isNotBlank()) {
                val alertsByHandle = runCatching {
                    db.collection("moderation_alerts")
                        .whereEqualTo("reportedUsername", cleanHandle)
                        .get()
                        .await()
                }.getOrNull()
                alertsByHandle?.documents?.forEach { doc ->
                    if (updatedAlertIds.add(doc.id)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "status" to "recovered",
                                "adminEmail" to ADMIN_GMAIL,
                                "resolvedAtMs" to resolvedNowMs,
                                "updatedAt" to nowTime
                            )
                        )
                    }
                }
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
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

    sealed interface UsernameAuthResult {
        data class Success(
            val userId: String,
            val username: String,
            val displayName: String,
            val dob: String
        ) : UsernameAuthResult

        data class Banned(
            val userId: String,
            val username: String,
            val displayName: String,
            val photoData: String?,
            val message: String = "Your ID is banned"
        ) : UsernameAuthResult

        data class Error(val message: String) : UsernameAuthResult
    }

    data class LegacyMigrationPayload(
        val username: String,
        val displayName: String,
        val dob: String,
        val bio: String,
        val photoData: String?,
        val bannerData: String?,
        val passwordSalt: String,
        val passwordHash: String,
        val accountStatus: String,
        val contacts: List<ChatThread> = emptyList(),
        val messages: Map<String, List<ChatMessage>> = emptyMap()
    )

    companion object {
        const val PRESENCE_STALE_MS = 150_000L

        fun getPersistentDb(context: Context): FirebaseFirestore {
            val app = context.applicationContext
            val databaseId = app.getString(R.string.firestore_database_id)
            return FirebaseFirestore.getInstance(databaseId).apply {
                runCatching {
                    firestoreSettings = FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                        .build()
                }
            }
        }

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
            val syncedUsername = entry.username.trim().ifBlank { thread.username }
            val syncedPhone = if (syncedUsername.isNotBlank() && thread.phone.startsWith("@")) {
                "@$syncedUsername"
            } else {
                thread.phone
            }
            val syncedStatus = if (entry.accountStatus == "banned" || entry.bannedByAdmin) "banned" else "active"
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
            val syncedBio = if (visibleToMeSafe(visibleToViewer)) {
                entry.bio.trim().ifBlank { thread.bio }
            } else ""
            val syncedVerified = entry.verified && syncedStatus != "banned"
            val updatedMs = entry.updatedAt?.toDate()?.time
            val withinWindow = updatedMs == null || (nowMillis - updatedMs) <= PRESENCE_STALE_MS
            val realOnline = entry.showOnlineStatus && entry.online && syncedStatus != "banned" && withinWindow
            return thread.copy(
                name = syncedName,
                username = syncedUsername,
                phone = syncedPhone,
                bio = syncedBio,
                photoData = syncedPhoto,
                bannerData = syncedBanner,
                online = realOnline,
                verified = syncedVerified,
                accountStatus = syncedStatus,
                peerUid = thread.peerUid.ifBlank { entry.ownerUid.orEmpty() },
                isRegisteredPeer = thread.isRegisteredPeer || !entry.ownerUid.isNullOrBlank()
            )
        }

        private fun visibleToMeSafe(flag: Boolean): Boolean = flag

        suspend fun signUpUsernameAccount(
            db: FirebaseFirestore,
            fullName: String,
            usernameInput: String,
            dob: String,
            password: String,
            rePassword: String,
            localAuthStore: com.example.data.local.LocalAuthStore? = null
        ): UsernameAuthResult {
            val cleanName = fullName.trim().take(60)
            val cleanHandle = exactUsernameOrNull(usernameInput)
            val cleanDob = dob.trim().take(20)

            if (cleanName.isEmpty() || usernameInput.trim().isEmpty() || cleanDob.isEmpty() ||
                password.isEmpty() || rePassword.isEmpty()
            ) {
                return UsernameAuthResult.Error("Please fill in all fields.")
            }
            if (cleanHandle == null) {
                val stripped = normalizeUsername(usernameInput)
                return if (stripped.length < 2) {
                    UsernameAuthResult.Error("Username must be at least 2 characters.")
                } else {
                    UsernameAuthResult.Error("Username cannot contain spaces or special characters.")
                }
            }
            if (password.length < 6) {
                return UsernameAuthResult.Error("Password must be at least 6 characters.")
            }
            if (password != rePassword) {
                return UsernameAuthResult.Error("Passwords do not match.")
            }

            val uid = PasswordHasher.generateUsernameUid()
            val salt = PasswordHasher.generateSalt()
            val hash = PasswordHasher.hashPassword(password, salt)
            val defaultBio = "Dream Big... 💚"

            return try {
                runCatching { db.enableNetwork() }
                val acctRef = db.collection("username_accounts").document(cleanHandle)
                val usernameRef = db.collection("usernames").document(cleanHandle)
                val dirRef = db.collection("directory").document(cleanHandle)

                val (acctSnap, usernameSnap, dirSnap) = coroutineScope {
                    val acctTask = async { withTimeoutOrNull(4000L) { runCatching { acctRef.get(Source.SERVER).await() }.getOrNull() } }
                    val userTask = async { withTimeoutOrNull(4000L) { runCatching { usernameRef.get(Source.SERVER).await() }.getOrNull() } }
                    val dirTask = async { withTimeoutOrNull(4000L) { runCatching { dirRef.get(Source.SERVER).await() }.getOrNull() } }
                    Triple(acctTask.await(), userTask.await(), dirTask.await())
                }

                val reservationTaken = usernameSnap != null && usernameSnap.exists() &&
                    usernameSnap.getString("status") == "active" &&
                    usernameSnap.getString("ownerUid").orEmpty().isNotBlank()
                if ((acctSnap != null && acctSnap.exists()) || (dirSnap != null && dirSnap.exists()) || reservationTaken) {
                    return UsernameAuthResult.Error("This username is already taken. Please choose another.")
                }

                val nowTime = FieldValue.serverTimestamp()

                val batch = db.batch()
                batch.set(
                    db.collection("users").document(uid),
                    mapOf(
                        "userId" to uid,
                        "displayName" to cleanName,
                        "username" to cleanHandle,
                        "email" to "",
                        "bio" to defaultBio,
                        "dob" to cleanDob,
                        "provider" to "username",
                        "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                        "allowedViewerUids" to emptyList<String>(),
                        "maxProfileViewers" to 0L,
                        "online" to true,
                        "showOnlineStatus" to true,
                        "verified" to false,
                        "premiumUnlocked" to true,
                        "accountStatus" to "active",
                        "reportCount" to 0L,
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )
                batch.set(
                    acctRef,
                    mapOf(
                        "userId" to uid,
                        "username" to cleanHandle,
                        "displayName" to cleanName,
                        "dob" to cleanDob,
                        "passwordSalt" to salt,
                        "passwordHash" to hash,
                        "accountStatus" to "active",
                        "bannedByAdmin" to false,
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )
                batch.set(
                    db.collection("users").document(uid).collection("credentials").document(cleanHandle),
                    mapOf(
                        "userId" to uid,
                        "username" to cleanHandle,
                        "displayName" to cleanName,
                        "dob" to cleanDob,
                        "passwordSalt" to salt,
                        "passwordHash" to hash,
                        "accountStatus" to "active",
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )
                if (usernameSnap != null && usernameSnap.exists()) {
                    batch.update(
                        usernameRef,
                        mapOf(
                            "ownerUid" to uid,
                            "status" to "active",
                            "updatedAt" to nowTime
                        )
                    )
                } else {
                    batch.set(
                        usernameRef,
                        mapOf(
                            "username" to cleanHandle,
                            "ownerUid" to uid,
                            "status" to "active",
                            "createdAt" to nowTime,
                            "updatedAt" to nowTime
                        )
                    )
                }
                batch.set(
                    dirRef,
                    mapOf(
                        "ownerUid" to uid,
                        "username" to cleanHandle,
                        "displayName" to cleanName,
                        "bio" to defaultBio,
                        "provider" to "username",
                        "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                        "allowedViewerUids" to emptyList<String>(),
                        "maxProfileViewers" to 0L,
                        "online" to true,
                        "showOnlineStatus" to true,
                        "verified" to false,
                        "accountStatus" to "active",
                        "reportCount" to 0L,
                        "reportedByUids" to emptyList<String>(),
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )
                batch.set(
                    db.collection("public_profiles").document(uid),
                    mapOf(
                        "ownerUid" to uid,
                        "username" to cleanHandle,
                        "displayName" to cleanName,
                        "bio" to defaultBio,
                        "provider" to "username",
                        "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                        "allowedViewerUids" to emptyList<String>(),
                        "maxProfileViewers" to 0L,
                        "online" to true,
                        "showOnlineStatus" to true,
                        "verified" to false,
                        "accountStatus" to "active",
                        "bannedByAdmin" to false,
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )

                withTimeoutOrNull(10_000L) {
                    runCatching { batch.commit().await() }.isSuccess
                }

                // Silently store in local auth store as well for long term resilience
                localAuthStore?.signUpUser(
                    fullName = cleanName,
                    usernameInput = cleanHandle,
                    dob = cleanDob,
                    password = password,
                    uid = uid
                )

                UsernameAuthResult.Success(
                    userId = uid,
                    username = cleanHandle,
                    displayName = cleanName,
                    dob = cleanDob
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.localizedMessage.orEmpty()
                if (msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
                    msg.contains("Missing or insufficient permissions", ignoreCase = true)
                ) {
                    // Graceful fallback without showing red permission error
                    localAuthStore?.signUpUser(
                        fullName = cleanName,
                        usernameInput = cleanHandle,
                        dob = cleanDob,
                        password = password,
                        uid = uid
                    )
                    return UsernameAuthResult.Success(
                        userId = uid,
                        username = cleanHandle,
                        displayName = cleanName,
                        dob = cleanDob
                    )
                }
                UsernameAuthResult.Error(e.localizedMessage ?: "Unable to create account.")
            }
        }

        suspend fun loginUsernameAccount(
            db: FirebaseFirestore,
            usernameInput: String,
            password: String,
            localAuthStore: com.example.data.local.LocalAuthStore? = null
        ): UsernameAuthResult {
            val cleanHandle = exactUsernameOrNull(usernameInput)
            if (usernameInput.trim().isEmpty() || password.isEmpty()) {
                return UsernameAuthResult.Error("Please enter both username and password.")
            }
            if (cleanHandle == null) {
                return if (normalizeUsername(usernameInput).length < 2) {
                    UsernameAuthResult.Error("Account Not Found")
                } else {
                    UsernameAuthResult.Error("Username cannot contain spaces or special characters.")
                }
            }

            return try {
                runCatching { db.enableNetwork() }
                val acctRef = db.collection("username_accounts").document(cleanHandle)
                val acctSnap = withTimeoutOrNull(5000L) {
                    runCatching { acctRef.get(Source.SERVER).await() }.getOrNull()
                } ?: runCatching { acctRef.get().await() }.getOrNull()

                if (acctSnap == null) {
                    return UsernameAuthResult.Error("Unable to reach Firebase server. Please check your internet connection.")
                }
                if (!acctSnap.exists()) {
                    return UsernameAuthResult.Error("Account Not Found")
                }

                val uid = acctSnap.getString("userId").orEmpty().trim()
                val salt = acctSnap.getString("passwordSalt").orEmpty()
                val expectedHash = acctSnap.getString("passwordHash").orEmpty()
                val storedName = acctSnap.getString("displayName").orEmpty().ifBlank { cleanHandle }.take(60)
                val storedDob = acctSnap.getString("dob").orEmpty().ifBlank { "2000-01-01" }.take(20)

                if (uid.isBlank() || !PasswordHasher.verifyPassword(password, salt, expectedHash)) {
                    return UsernameAuthResult.Error("Invalid username or password.")
                }

                val userRef = db.collection("users").document(uid)
                val dirRef = db.collection("directory").document(cleanHandle)
                val pubRef = db.collection("public_profiles").document(uid)
                val resRef = db.collection("usernames").document(cleanHandle)

                val (userSnap, dirSnap, pubSnap, resSnap) = coroutineScope {
                    val uTask = async { withTimeoutOrNull(3500L) { runCatching { userRef.get(Source.SERVER).await() }.getOrNull() } ?: runCatching { userRef.get().await() }.getOrNull() }
                    val dTask = async { withTimeoutOrNull(3500L) { runCatching { dirRef.get(Source.SERVER).await() }.getOrNull() } ?: runCatching { dirRef.get().await() }.getOrNull() }
                    val pTask = async { withTimeoutOrNull(3500L) { runCatching { pubRef.get(Source.SERVER).await() }.getOrNull() } ?: runCatching { pubRef.get().await() }.getOrNull() }
                    val rTask = async { withTimeoutOrNull(3500L) { runCatching { resRef.get(Source.SERVER).await() }.getOrNull() } ?: runCatching { resRef.get().await() }.getOrNull() }
                    listOf(uTask.await(), dTask.await(), pTask.await(), rTask.await())
                }

                val isBanned = acctSnap.getString("accountStatus") == "banned" ||
                    acctSnap.getBoolean("bannedByAdmin") == true ||
                    userSnap?.getString("accountStatus") == "banned" ||
                    userSnap?.getBoolean("bannedByAdmin") == true ||
                    dirSnap?.getString("accountStatus") == "banned" ||
                    dirSnap?.getBoolean("bannedByAdmin") == true ||
                    pubSnap?.getString("accountStatus") == "banned" ||
                    pubSnap?.getBoolean("bannedByAdmin") == true

                val effectiveName = userSnap?.getString("displayName").orEmpty().ifBlank { storedName }.take(60)
                val effectivePhoto = userSnap?.getString("photoData") ?: dirSnap?.getString("photoData")
                if (isBanned) {
                    return UsernameAuthResult.Banned(
                        userId = uid,
                        username = cleanHandle,
                        displayName = effectiveName,
                        photoData = effectivePhoto
                    )
                }

                // Auto-heal missing profile/directory/public_profiles mirrors and upgrade legacy SHA-256 hash to PBKDF2
                val healBatch = db.batch()
                var hasWrites = false
                val nowTime = FieldValue.serverTimestamp()
                val effectiveBio = userSnap?.getString("bio").orEmpty().ifBlank { "Dream Big... 💚" }.take(200)
                val effectiveDob = userSnap?.getString("dob").orEmpty().ifBlank { storedDob }.take(20)
                val effectiveVisibility = ProfileVisibilityMode.fromKey(userSnap?.getString("profileVisibility")).key
                val effectiveShowOnline = userSnap?.getBoolean("showOnlineStatus") ?: true
                val effectiveBanner = userSnap?.getString("bannerData")?.takeIf { it.isNotBlank() && it.length <= 250000 }

                if (userSnap == null || !userSnap.exists()) {
                    val userPayload = mutableMapOf<String, Any>(
                        "userId" to uid,
                        "displayName" to effectiveName,
                        "username" to cleanHandle,
                        "email" to "",
                        "bio" to effectiveBio,
                        "dob" to effectiveDob,
                        "provider" to "username",
                        "profileVisibility" to effectiveVisibility,
                        "allowedViewerUids" to emptyList<String>(),
                        "maxProfileViewers" to 0L,
                        "online" to effectiveShowOnline,
                        "showOnlineStatus" to effectiveShowOnline,
                        "verified" to false,
                        "premiumUnlocked" to true,
                        "accountStatus" to "active",
                        "reportCount" to 0L,
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                    if (!effectivePhoto.isNullOrBlank() && effectivePhoto.length <= 250000) userPayload["photoData"] = effectivePhoto
                    if (!effectiveBanner.isNullOrBlank()) userPayload["bannerData"] = effectiveBanner
                    healBatch.set(userRef, userPayload)
                    hasWrites = true
                }
                if (dirSnap == null || !dirSnap.exists()) {
                    val dirPayload = mutableMapOf<String, Any>(
                        "ownerUid" to uid,
                        "username" to cleanHandle,
                        "displayName" to effectiveName,
                        "bio" to effectiveBio,
                        "provider" to "username",
                        "profileVisibility" to effectiveVisibility,
                        "allowedViewerUids" to emptyList<String>(),
                        "maxProfileViewers" to 0L,
                        "online" to effectiveShowOnline,
                        "showOnlineStatus" to effectiveShowOnline,
                        "verified" to false,
                        "accountStatus" to "active",
                        "reportCount" to 0L,
                        "reportedByUids" to emptyList<String>(),
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                    if (!effectivePhoto.isNullOrBlank() && effectivePhoto.length <= 250000) dirPayload["photoData"] = effectivePhoto
                    if (!effectiveBanner.isNullOrBlank()) dirPayload["bannerData"] = effectiveBanner
                    healBatch.set(dirRef, dirPayload)
                    hasWrites = true
                }
                if (pubSnap == null || !pubSnap.exists()) {
                    val pubPayload = mutableMapOf<String, Any>(
                        "ownerUid" to uid,
                        "username" to cleanHandle,
                        "displayName" to effectiveName,
                        "bio" to effectiveBio,
                        "provider" to "username",
                        "profileVisibility" to effectiveVisibility,
                        "allowedViewerUids" to emptyList<String>(),
                        "maxProfileViewers" to 0L,
                        "online" to effectiveShowOnline,
                        "showOnlineStatus" to effectiveShowOnline,
                        "verified" to false,
                        "accountStatus" to "active",
                        "bannedByAdmin" to false,
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                    if (!effectivePhoto.isNullOrBlank() && effectivePhoto.length <= 250000) pubPayload["photoData"] = effectivePhoto
                    if (!effectiveBanner.isNullOrBlank()) pubPayload["bannerData"] = effectiveBanner
                    healBatch.set(pubRef, pubPayload)
                    hasWrites = true
                }
                if (resSnap == null || !resSnap.exists()) {
                    healBatch.set(
                        resRef,
                        mapOf(
                            "username" to cleanHandle,
                            "ownerUid" to uid,
                            "status" to "active",
                            "createdAt" to nowTime,
                            "updatedAt" to nowTime
                        )
                    )
                    hasWrites = true
                }
                if (!salt.startsWith("v2_")) {
                    val upgradedSalt = PasswordHasher.generateSalt()
                    val upgradedHash = PasswordHasher.hashPassword(password, upgradedSalt)
                    healBatch.update(
                        acctRef,
                        mapOf(
                            "passwordSalt" to upgradedSalt,
                            "passwordHash" to upgradedHash,
                            "updatedAt" to nowTime
                        )
                    )
                    hasWrites = true
                }
                if (hasWrites) {
                    runCatching { healBatch.commit().await() }
                }

                // Restore all messages & voice messages and sync contacts bidirectional
                restoreAndSyncAccountData(
                    db = db,
                    userId = uid,
                    username = cleanHandle,
                    localStore = localAuthStore
                )

                UsernameAuthResult.Success(
                    userId = uid,
                    username = cleanHandle,
                    displayName = effectiveName,
                    dob = effectiveDob
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsernameAuthResult.Error(e.localizedMessage ?: "Unable to log in.")
            }
        }

        suspend fun restoreAndSyncAccountData(
            db: FirebaseFirestore,
            userId: String,
            username: String,
            localStore: com.example.data.local.LocalAuthStore?
        ) = withContext(Dispatchers.IO) {
            val cleanUid = userId.trim()
            val cleanHandle = normalizeUsername(username).take(40)
            if (cleanUid.isBlank() || cleanHandle.isBlank()) return@withContext

            try {
                val contactsSnap = withTimeoutOrNull(6000L) {
                    runCatching {
                        db.collection("users").document(cleanUid).collection("contacts").get().await()
                    }.getOrNull()
                }
                val firestoreContactDocs = contactsSnap?.documents.orEmpty()
                val firestoreContactMap = firestoreContactDocs.associateBy { it.id }

                if (localStore != null) {
                    val (localContacts, localMessages) = localStore.getAccountContactsAndMessages(cleanHandle)

                    // Upload local contacts that are not in Firestore
                    for (c in localContacts) {
                        val cDoc = firestoreContactMap[c.id]
                        if (cDoc == null || !cDoc.exists()) {
                            val cPayload = mutableMapOf<String, Any>(
                                "userId" to cleanUid,
                                "name" to c.name,
                                "username" to c.username,
                                "phone" to c.phone,
                                "threadType" to c.threadType,
                                "bio" to c.bio,
                                "online" to c.online,
                                "typing" to c.typing,
                                "unreadCount" to c.unreadCount,
                                "lastMessageText" to c.lastMessageText,
                                "lastMessageFromMe" to c.lastMessageFromMe,
                                "lastMessageTimeLabel" to c.lastMessageTimeLabel,
                                "pinned" to c.pinned,
                                "locked" to c.locked,
                                "blocked" to c.blocked,
                                "archived" to c.archived,
                                "muted" to c.muted,
                                "disappearingSeconds" to c.disappearingSeconds,
                                "chatTheme" to c.chatTheme,
                                "customRenamed" to c.customRenamed,
                                "peerUid" to c.peerUid,
                                "isRegisteredPeer" to c.isRegisteredPeer,
                                "accountStatus" to c.accountStatus,
                                "reportCount" to c.reportCount,
                                "reportedBy" to c.reportedBy,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            if (!c.photoData.isNullOrBlank() && c.photoData.length <= 250000) cPayload["photoData"] = c.photoData
                            if (!c.bannerData.isNullOrBlank() && c.bannerData.length <= 250000) cPayload["bannerData"] = c.bannerData
                            if (c.lastMessageId.isNotBlank()) cPayload["lastMessageId"] = c.lastMessageId
                            if (c.lastMessageReceiptStatus.isNotBlank()) cPayload["lastMessageReceiptStatus"] = c.lastMessageReceiptStatus

                            runCatching {
                                db.collection("users").document(cleanUid).collection("contacts").document(c.id).set(cPayload).await()
                            }
                        }

                        val msgs = localMessages[c.id].orEmpty()
                        for (m in msgs) {
                            val msgId = m.id.ifBlank { UUID.randomUUID().toString() }
                            val msgRef = db.collection("users").document(cleanUid).collection("contacts").document(c.id).collection("messages").document(msgId)
                            val existingMsg = runCatching { msgRef.get().await() }.getOrNull()
                            if (existingMsg == null || !existingMsg.exists()) {
                                val msgPayload = mutableMapOf<String, Any>(
                                    "userId" to cleanUid,
                                    "contactId" to c.id,
                                    "sender" to m.sender,
                                    "text" to m.text.ifBlank { if (m.messageType == "voice") "Voice message" else "Message" },
                                    "messageType" to m.messageType,
                                    "timeLabel" to m.timeLabel.ifBlank { "Just now" },
                                    "aiGenerated" to m.aiGenerated,
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                                if (!m.mediaData.isNullOrBlank() && m.mediaData.length <= 240000) msgPayload["mediaData"] = m.mediaData
                                if (m.durationSec > 0) msgPayload["durationSec"] = m.durationSec
                                if (m.expiresAtMs > 0) msgPayload["expiresAtMs"] = m.expiresAtMs
                                if (!m.replyToText.isNullOrBlank()) msgPayload["replyToText"] = m.replyToText
                                if (!m.replyToSender.isNullOrBlank()) msgPayload["replyToSender"] = m.replyToSender
                                if (!m.replyToMessageId.isNullOrBlank()) msgPayload["replyToMessageId"] = m.replyToMessageId
                                if (!m.receiptStatus.isNullOrBlank()) msgPayload["receiptStatus"] = m.receiptStatus

                                runCatching { msgRef.set(msgPayload).await() }
                            }
                        }
                    }

                    // Download/refresh contacts and messages from Firestore into localStore
                    val latestContactsSnap = runCatching {
                        db.collection("users").document(cleanUid).collection("contacts").get().await()
                    }.getOrNull()
                    val cloudContacts = mutableListOf<ChatThread>()
                    val cloudMessages = mutableMapOf<String, List<ChatMessage>>()

                    latestContactsSnap?.documents?.forEach { cDoc ->
                        val thread = cDoc.toObject(ChatThread::class.java)?.copy(id = cDoc.id)
                        if (thread != null) {
                            cloudContacts.add(thread)
                            val mSnap = runCatching {
                                cDoc.reference.collection("messages").orderBy("createdAt", Query.Direction.ASCENDING).get().await()
                            }.getOrNull()
                            val mList = mSnap?.documents?.mapNotNull { mDoc ->
                                mDoc.toObject(ChatMessage::class.java)?.copy(id = mDoc.id)
                            }.orEmpty()
                            if (mList.isNotEmpty()) {
                                cloudMessages[cDoc.id] = mList
                            }
                        }
                    }

                    if (cloudContacts.isNotEmpty() || cloudMessages.isNotEmpty()) {
                        localStore.importContactsAndMessages(cleanHandle, cloudContacts, cloudMessages)
                    }
                }
            } catch (e: Exception) {
                // Background sync fails gracefully without blocking auth
            }
        }

        suspend fun migrateLegacyLocalAccountToFirebase(
            db: FirebaseFirestore,
            legacy: LegacyMigrationPayload,
            rawPassword: String
        ): UsernameAuthResult {
            val cleanHandle = exactUsernameOrNull(legacy.username)
                ?: return UsernameAuthResult.Error("Account Not Found")
            if (!PasswordHasher.verifyPassword(rawPassword, legacy.passwordSalt, legacy.passwordHash)) {
                return UsernameAuthResult.Error("Invalid username or password.")
            }
            if (legacy.accountStatus == "banned") {
                return UsernameAuthResult.Banned(
                    userId = PasswordHasher.generateUsernameUid(),
                    username = cleanHandle,
                    displayName = legacy.displayName,
                    photoData = legacy.photoData
                )
            }

            return try {
                val acctRef = db.collection("username_accounts").document(cleanHandle)
                val usernameRef = db.collection("usernames").document(cleanHandle)
                val dirRef = db.collection("directory").document(cleanHandle)
                val acctSnap = withTimeoutOrNull(4000L) { acctRef.get(Source.SERVER).await() }
                    ?: return UsernameAuthResult.Error("Unable to sync account with Firebase.")
                if (acctSnap.exists()) {
                    return loginUsernameAccount(db, cleanHandle, rawPassword, null)
                }
                val uid = PasswordHasher.generateUsernameUid()
                val salt = PasswordHasher.generateSalt()
                val hash = PasswordHasher.hashPassword(rawPassword, salt)
                val cleanName = legacy.displayName.trim().ifBlank { cleanHandle }.take(60)
                val cleanDob = legacy.dob.trim().ifBlank { "2000-01-01" }.take(20)
                val cleanBio = legacy.bio.trim().ifBlank { "Dream Big... 💚" }.take(200)
                val nowTime = FieldValue.serverTimestamp()
                val safePhoto = legacy.photoData?.takeIf { it.isNotBlank() && it.length <= 250000 }
                val safeBanner = legacy.bannerData?.takeIf { it.isNotBlank() && it.length <= 250000 }

                val batch = db.batch()
                val userPayload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to cleanName,
                    "username" to cleanHandle,
                    "email" to "",
                    "bio" to cleanBio,
                    "dob" to cleanDob,
                    "provider" to "username",
                    "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                    "allowedViewerUids" to emptyList<String>(),
                    "maxProfileViewers" to 0L,
                    "online" to true,
                    "showOnlineStatus" to true,
                    "verified" to false,
                    "premiumUnlocked" to true,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "createdAt" to nowTime,
                    "updatedAt" to nowTime
                )
                if (safePhoto != null) userPayload["photoData"] = safePhoto
                if (safeBanner != null) userPayload["bannerData"] = safeBanner
                batch.set(db.collection("users").document(uid), userPayload)
                batch.set(
                    acctRef,
                    mapOf(
                        "userId" to uid,
                        "username" to cleanHandle,
                        "displayName" to cleanName,
                        "dob" to cleanDob,
                        "passwordSalt" to salt,
                        "passwordHash" to hash,
                        "accountStatus" to "active",
                        "bannedByAdmin" to false,
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )
                batch.set(
                    db.collection("users").document(uid).collection("credentials").document(cleanHandle),
                    mapOf(
                        "userId" to uid,
                        "username" to cleanHandle,
                        "displayName" to cleanName,
                        "dob" to cleanDob,
                        "passwordSalt" to salt,
                        "passwordHash" to hash,
                        "accountStatus" to "active",
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                )
                val resSnap = runCatching { usernameRef.get(Source.SERVER).await() }.getOrNull()
                if (resSnap != null && resSnap.exists() && resSnap.getString("status") == "retired") {
                    batch.update(usernameRef, mapOf("ownerUid" to uid, "status" to "active", "updatedAt" to nowTime))
                } else if (resSnap == null || !resSnap.exists()) {
                    batch.set(
                        usernameRef,
                        mapOf("username" to cleanHandle, "ownerUid" to uid, "status" to "active", "createdAt" to nowTime, "updatedAt" to nowTime)
                    )
                }
                val dirPayload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "username" to cleanHandle,
                    "displayName" to cleanName,
                    "bio" to cleanBio,
                    "provider" to "username",
                    "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                    "allowedViewerUids" to emptyList<String>(),
                    "maxProfileViewers" to 0L,
                    "online" to true,
                    "showOnlineStatus" to true,
                    "verified" to false,
                    "accountStatus" to "active",
                    "reportCount" to 0L,
                    "reportedByUids" to emptyList<String>(),
                    "createdAt" to nowTime,
                    "updatedAt" to nowTime
                )
                if (safePhoto != null) dirPayload["photoData"] = safePhoto
                if (safeBanner != null) dirPayload["bannerData"] = safeBanner
                val dirExisting = runCatching { dirRef.get(Source.SERVER).await() }.getOrNull()
                if (dirExisting == null || !dirExisting.exists()) {
                    batch.set(dirRef, dirPayload)
                }
                val pubPayload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "username" to cleanHandle,
                    "displayName" to cleanName,
                    "bio" to cleanBio,
                    "provider" to "username",
                    "profileVisibility" to ProfileVisibilityMode.EVERYONE.key,
                    "allowedViewerUids" to emptyList<String>(),
                    "maxProfileViewers" to 0L,
                    "online" to true,
                    "showOnlineStatus" to true,
                    "verified" to false,
                    "accountStatus" to "active",
                    "bannedByAdmin" to false,
                    "createdAt" to nowTime,
                    "updatedAt" to nowTime
                )
                if (safePhoto != null) pubPayload["photoData"] = safePhoto
                if (safeBanner != null) pubPayload["bannerData"] = safeBanner
                batch.set(db.collection("public_profiles").document(uid), pubPayload)

                batch.commit().await()

                // Restore legacy contacts and messages to Firestore
                if (legacy.contacts.isNotEmpty() || legacy.messages.isNotEmpty()) {
                    for (c in legacy.contacts) {
                        val cPayload = mutableMapOf<String, Any>(
                            "userId" to uid,
                            "name" to c.name,
                            "username" to c.username,
                            "phone" to c.phone,
                            "threadType" to c.threadType,
                            "bio" to c.bio,
                            "online" to c.online,
                            "typing" to c.typing,
                            "unreadCount" to c.unreadCount,
                            "lastMessageText" to c.lastMessageText,
                            "lastMessageFromMe" to c.lastMessageFromMe,
                            "lastMessageTimeLabel" to c.lastMessageTimeLabel,
                            "pinned" to c.pinned,
                            "locked" to c.locked,
                            "blocked" to c.blocked,
                            "archived" to c.archived,
                            "muted" to c.muted,
                            "disappearingSeconds" to c.disappearingSeconds,
                            "chatTheme" to c.chatTheme,
                            "customRenamed" to c.customRenamed,
                            "peerUid" to c.peerUid,
                            "isRegisteredPeer" to c.isRegisteredPeer,
                            "accountStatus" to c.accountStatus,
                            "reportCount" to c.reportCount,
                            "reportedBy" to c.reportedBy,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (!c.photoData.isNullOrBlank() && c.photoData.length <= 250000) cPayload["photoData"] = c.photoData
                        if (!c.bannerData.isNullOrBlank() && c.bannerData.length <= 250000) cPayload["bannerData"] = c.bannerData
                        if (c.lastMessageId.isNotBlank()) cPayload["lastMessageId"] = c.lastMessageId
                        if (c.lastMessageReceiptStatus.isNotBlank()) cPayload["lastMessageReceiptStatus"] = c.lastMessageReceiptStatus
                        runCatching {
                            db.collection("users").document(uid).collection("contacts").document(c.id).set(cPayload).await()
                        }

                        val msgs = legacy.messages[c.id].orEmpty()
                        for (m in msgs) {
                            val msgId = m.id.ifBlank { UUID.randomUUID().toString() }
                            val msgPayload = mutableMapOf<String, Any>(
                                "userId" to uid,
                                "contactId" to c.id,
                                "sender" to m.sender,
                                "text" to m.text.ifBlank { if (m.messageType == "voice") "Voice message" else "Message" },
                                "messageType" to m.messageType,
                                "timeLabel" to m.timeLabel.ifBlank { "Just now" },
                                "aiGenerated" to m.aiGenerated,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                            if (!m.mediaData.isNullOrBlank() && m.mediaData.length <= 240000) msgPayload["mediaData"] = m.mediaData
                            if (m.durationSec > 0) msgPayload["durationSec"] = m.durationSec
                            if (m.expiresAtMs > 0) msgPayload["expiresAtMs"] = m.expiresAtMs
                            if (!m.replyToText.isNullOrBlank()) msgPayload["replyToText"] = m.replyToText
                            if (!m.replyToSender.isNullOrBlank()) msgPayload["replyToSender"] = m.replyToSender
                            if (!m.replyToMessageId.isNullOrBlank()) msgPayload["replyToMessageId"] = m.replyToMessageId
                            if (!m.receiptStatus.isNullOrBlank()) msgPayload["receiptStatus"] = m.receiptStatus
                            runCatching {
                                db.collection("users").document(uid).collection("contacts").document(c.id).collection("messages").document(msgId).set(msgPayload).await()
                            }
                        }
                    }
                }

                UsernameAuthResult.Success(uid, cleanHandle, cleanName, cleanDob)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsernameAuthResult.Error(e.localizedMessage ?: "Unable to migrate account.")
            }
        }

        suspend fun validateUsernameSession(
            db: FirebaseFirestore,
            userId: String,
            username: String
        ): UsernameAuthResult {
            val cleanUid = userId.trim()
            val cleanHandle = exactUsernameOrNull(username).orEmpty()
            if (!cleanUid.startsWith("usr_") || cleanHandle.isBlank()) {
                return UsernameAuthResult.Error("Session expired.")
            }
            return try {
                val userRef = db.collection("users").document(cleanUid)
                val cachedUser = runCatching { userRef.get(Source.CACHE).await() }.getOrNull()
                val freshUser = withTimeoutOrNull(1800L) {
                    runCatching { userRef.get(Source.SERVER).await() }.getOrNull()
                }
                val userSnap = freshUser ?: cachedUser

                val dirSnap = withTimeoutOrNull(1500L) {
                    runCatching { db.collection("directory").document(cleanHandle).get(Source.SERVER).await() }.getOrNull()
                } ?: runCatching { db.collection("directory").document(cleanHandle).get(Source.CACHE).await() }.getOrNull()

                val acctSnap = withTimeoutOrNull(1500L) {
                    runCatching { db.collection("username_accounts").document(cleanHandle).get(Source.SERVER).await() }.getOrNull()
                } ?: runCatching { db.collection("username_accounts").document(cleanHandle).get(Source.CACHE).await() }.getOrNull()

                val isBanned = userSnap?.getString("accountStatus") == "banned" ||
                    userSnap?.getBoolean("bannedByAdmin") == true ||
                    dirSnap?.getString("accountStatus") == "banned" ||
                    dirSnap?.getBoolean("bannedByAdmin") == true ||
                    acctSnap?.getString("accountStatus") == "banned" ||
                    acctSnap?.getBoolean("bannedByAdmin") == true

                val effectiveName = userSnap?.getString("displayName")
                    ?: acctSnap?.getString("displayName")
                    ?: cleanHandle
                val effectiveHandle = userSnap?.getString("username")?.takeIf { it.isNotBlank() } ?: cleanHandle

                if (isBanned) {
                    return UsernameAuthResult.Banned(
                        userId = cleanUid,
                        username = effectiveHandle,
                        displayName = effectiveName,
                        photoData = userSnap?.getString("photoData")
                    )
                }

                // Only reject if server explicitly confirmed account doesn't exist in both users and username_accounts
                if (freshUser != null && !freshUser.exists() && acctSnap != null && !acctSnap.exists()) {
                    return UsernameAuthResult.Error("Account Not Found")
                }

                UsernameAuthResult.Success(
                    userId = cleanUid,
                    username = effectiveHandle,
                    displayName = effectiveName,
                    dob = userSnap?.getString("dob") ?: "2000-01-01"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Rely on persistent cache when offline so the user enters smoothly
                UsernameAuthResult.Success(cleanUid, cleanHandle, cleanHandle, "2000-01-01")
            }
        }

        suspend fun resolveAccountUidByUsername(
            db: FirebaseFirestore,
            username: String
        ): Pair<String, String>? {
            val cleanHandle = exactUsernameOrNull(username) ?: return null
            val acctSnap = withTimeoutOrNull(2500L) {
                runCatching { db.collection("username_accounts").document(cleanHandle).get(Source.SERVER).await() }.getOrNull()
            } ?: runCatching { db.collection("username_accounts").document(cleanHandle).get().await() }.getOrNull()
            val uid = acctSnap?.getString("userId").orEmpty().trim()
            if (uid.startsWith("usr_")) {
                val name = acctSnap?.getString("displayName").orEmpty().ifBlank { cleanHandle }
                return uid to name
            }
            return null
        }

        suspend fun submitBannedAppealToFirestore(
            db: FirebaseFirestore,
            userId: String,
            username: String,
            displayName: String,
            appealMessage: String,
            aiRecommendation: String,
            aiRiskLevel: String,
            aiAnalysis: String,
            aiPrompt: String
        ): Result<Unit> {
            return try {
                val cleanUid = userId.trim()
                val cleanHandle = exactUsernameOrNull(username)
                    ?: normalizeUsername(username).ifBlank { "user" }
                val cleanName = displayName.trim().ifBlank { cleanHandle }.take(60)
                val cleanAppeal = appealMessage.trim().take(600)
                require(cleanUid.startsWith("usr_")) { "Invalid username account ID." }
                require(cleanAppeal.isNotEmpty()) { "Please enter your appeal message." }

                val rec = aiRecommendation.trim().uppercase().takeIf {
                    it == "RESTORE" || it == "KEEP_BANNED"
                } ?: "KEEP_BANNED"
                val risk = aiRiskLevel.trim().uppercase().takeIf {
                    it == "LOW" || it == "MEDIUM" || it == "HIGH"
                } ?: "MEDIUM"
                val alertId = "appeal_${cleanHandle}_${System.currentTimeMillis()}"
                val nowTime = FieldValue.serverTimestamp()

                db.collection("moderation_alerts").document(alertId).set(
                    mapOf(
                        "alertType" to "appeal",
                        "reportedUid" to cleanUid,
                        "reportedUsername" to cleanHandle,
                        "reportedDisplayName" to cleanName,
                        "reporterUid" to cleanUid,
                        "reporterUsername" to cleanHandle,
                        "reason" to "Ban Appeal",
                        "appealMessage" to cleanAppeal,
                        "aiRecommendation" to rec,
                        "aiRiskLevel" to risk,
                        "aiAnalysis" to aiAnalysis.trim().take(400),
                        "aiPrompt" to aiPrompt.trim().take(300),
                        "status" to "pending",
                        "createdAtMs" to System.currentTimeMillis(),
                        "createdAt" to nowTime,
                        "updatedAt" to nowTime
                    )
                ).await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

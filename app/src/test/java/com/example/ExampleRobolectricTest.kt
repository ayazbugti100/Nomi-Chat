package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalAuthOutcome
import com.example.data.local.LocalAuthStore
import com.example.data.model.ChatThread
import com.example.data.model.IncomingMessageAlert
import com.example.data.model.STORY_DURATION_MS
import com.example.data.model.ThemeMode
import com.example.data.model.UserDirectoryEntry
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.repository.NomiChatRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, "nomi_auth_users.json").delete()
  }

  @Test
  fun `read string from context`() {
    val appName = context.getString(R.string.app_name)
    assertEquals("Nomi Chat", appName)
  }

  @Test
  fun `sign up and returning login validate credentials without google auth`() = runTest {
    val store = LocalAuthStore(context)

    val emptyResult = store.returningLogin("", "")
    assertEquals(LocalAuthOutcome.Error("Please enter both username and password."), emptyResult)

    val mismatch = store.signUpUser("Alex Roe", "alexroe", "2000-01-01", "pass123", "pass456")
    assertEquals(LocalAuthOutcome.Error("Passwords do not match."), mismatch)

    val created = store.signUpUser("Alex Roe", "alexroe", "2000-01-01", "pass123", "pass123")
    assertTrue(created is LocalAuthOutcome.Success)

    val duplicate = store.signUpUser("Other Name", "alexroe", "2000-01-01", "pass123", "pass123")
    assertEquals(LocalAuthOutcome.Error("This username is already taken."), duplicate)

    val wrongPass = store.returningLogin("alexroe", "wrongpass")
    assertEquals(LocalAuthOutcome.Error("Invalid username or password."), wrongPass)

    val loggedIn = store.returningLogin("  AlexRoe ", "pass123")
    assertEquals(LocalAuthOutcome.Success("alexroe"), loggedIn)
  }

  @Test
  fun `validateActiveSession detects missing, active, and banned accounts`() = runTest {
    val store = LocalAuthStore(context)

    val missing = store.validateActiveSession("nobody")
    assertEquals(LocalAuthOutcome.Error("User not found."), missing)

    val blank = store.validateActiveSession("   ")
    assertEquals(LocalAuthOutcome.Error("No active session."), blank)

    store.signUpUser("Sam Lee", "samlee", "1999-05-12", "secret12", "secret12")
    val active = store.validateActiveSession("  SamLee ")
    assertEquals(LocalAuthOutcome.Success("samlee"), active)

    val authFile = File(context.filesDir, "nomi_auth_users.json")
    val updatedJson = authFile.readText().replace("\"accountStatus\":\"active\"", "\"accountStatus\":\"banned\"")
    authFile.writeText(updatedJson)

    val banned = store.validateActiveSession("samlee")
    assertEquals(LocalAuthOutcome.Banned("Your account is banned."), banned)
  }

  @Test
  fun `updateProfile moves username record cleanly and rejects taken usernames`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Alex Roe", "alexroe", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Jamie Fox", "jamiefox", "1998-03-14", "pass123", "pass123")

    val takenResult = store.updateProfile(
      usernameKey = "alexroe",
      newUsername = "jamiefox"
    )
    assertTrue(takenResult.isFailure)
    assertEquals("This username is already taken.", takenResult.exceptionOrNull()?.message)

    val moveResult = store.updateProfile(
      usernameKey = "alexroe",
      displayName = "Alex R.",
      newUsername = "alex_new",
      bio = "Updated bio"
    )
    assertTrue(moveResult.isSuccess)
    assertEquals("alex_new", moveResult.getOrNull())

    val oldLogin = store.returningLogin("alexroe", "pass123")
    assertEquals(LocalAuthOutcome.Error("Invalid username or password."), oldLogin)

    val newLogin = store.returningLogin("alex_new", "pass123")
    assertEquals(LocalAuthOutcome.Success("alex_new"), newLogin)

    val movedProfile = store.observeProfile("alex_new").first()
    assertNotNull(movedProfile)
    assertEquals("Alex R.", movedProfile?.displayName)
    assertEquals("alex_new", movedProfile?.username)
    assertEquals("Updated bio", movedProfile?.bio)
  }

  @Test
  fun `account settings persist per user and restore across reloads`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Alex Roe", "alexroe", "2000-01-01", "pass123", "pass123")

    store.updateAccountSettings(
      usernameKey = "alexroe",
      themeMode = ThemeMode.DARK,
      notificationsEnabled = false,
      smartAiEnabled = false,
      appLockPinHash = "abcd1234"
    )

    val freshStore = LocalAuthStore(context)
    val saved = freshStore.getAccountSettings("alexroe")
    assertNotNull(saved)
    assertEquals(ThemeMode.DARK, saved?.themeMode)
    assertEquals(false, saved?.notificationsEnabled)
    assertEquals(false, saved?.smartAiEnabled)
    assertEquals("abcd1234", saved?.appLockPinHash)
  }

  @Test
  fun `lock block rename permanent delete and add by username persist across reloads`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Alex Roe", "alexroe", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Alex Nomi", "alex_nomi", "1998-07-04", "pass123", "pass123")

    val addRes = store.addContact(
      usernameKey = "alexroe",
      name = "",
      contactUsername = "@alex_nomi",
      phone = "",
      threadType = "chat"
    )
    assertTrue(addRes.isSuccess)
    val addedId = addRes.getOrThrow()

    var contacts = store.observeContacts("alexroe").first()
    val addedThread = contacts.firstOrNull { it.id == addedId }
    assertNotNull(addedThread)
    assertEquals("Alex Nomi", addedThread?.name)
    assertEquals("alex_nomi", addedThread?.username)

    store.renameChat("alexroe", addedId, "Best Buddy")
    store.toggleLockChat("alexroe", addedId, true)
    store.toggleBlockChat("alexroe", addedId, true)

    val reloadedStore = LocalAuthStore(context)
    val updatedThread = reloadedStore.observeContacts("alexroe").first().firstOrNull { it.id == addedId }
    assertNotNull(updatedThread)
    assertEquals("Best Buddy", updatedThread?.name)
    assertTrue(updatedThread?.customRenamed == true)
    assertTrue(updatedThread?.locked == true)
    assertTrue(updatedThread?.blocked == true)

    reloadedStore.deleteChatPermanently("alexroe", addedId)
    val afterDelete = LocalAuthStore(context).observeContacts("alexroe").first()
    assertTrue(afterDelete.none { it.id == addedId })
  }

  @Test
  fun `add chat by username rejects unregistered and self usernames`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Nomi User", "nomi_one", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Default Fallback", "nomiuser", "2000-01-01", "pass123", "pass123")
    store.signUpUser("GH Bugti", "ghbugti", "1997-02-10", "pass123", "pass123")

    // Any unregistered, blank, punctuation, or non-matching handle must be rejected cleanly
    val invalidInputs = listOf(
      "@does_not_exist",
      "GH Bugti",
      "gh.bugti",
      "gh_bugti",
      "@.",
      ""
    )
    invalidInputs.forEach { badInput ->
      val res = store.addContact(
        usernameKey = "nomi_one",
        name = "Fake Display Name",
        contactUsername = badInput,
        phone = "",
        threadType = "chat"
      )
      assertTrue("Expected failure for '$badInput'", res.isFailure)
      assertEquals("User not found", res.exceptionOrNull()?.message)
      assertEquals(null, store.lookupUserByUsername(badInput).getOrNull())
    }
    assertTrue(store.observeContacts("nomi_one").first().isEmpty())

    // Self-add is rejected
    val selfResult = store.addContact(
      usernameKey = "nomi_one",
      name = "",
      contactUsername = "@nomi_one",
      phone = "",
      threadType = "chat"
    )
    assertTrue(selfResult.isFailure)
    assertEquals("You cannot add your own username.", selfResult.exceptionOrNull()?.message)

    // Exact registered username succeeds and uses the real registered account name ("GH Bugti")
    val exactResult = store.addContact(
      usernameKey = "nomi_one",
      name = "Wrong Typed Name",
      contactUsername = "@ghbugti",
      phone = "",
      threadType = "chat"
    )
    assertTrue(exactResult.isSuccess)
    val addedContacts = store.observeContacts("nomi_one").first()
    assertEquals(1, addedContacts.size)
    assertEquals("GH Bugti", addedContacts.first().name)
    assertEquals("ghbugti", addedContacts.first().username)
  }

  @Test
  fun `dynamic unread count updates on incoming messages and cross-account lookup delivers cleanly`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Nomi User", "nomi", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Ali Khan", "ali_khan", "1999-04-10", "pass123", "pass123")

    val notFound = store.lookupUserByUsername("@nobody_here").getOrNull()
    assertEquals(null, notFound)

    val foundAli = store.lookupUserByUsername("@ali_khan").getOrNull()
    assertNotNull(foundAli)
    assertEquals("ali_khan", foundAli?.username)
    assertEquals("Ali Khan", foundAli?.displayName)

    val addRes = store.addContact(
      usernameKey = "nomi",
      name = foundAli!!.displayName,
      contactUsername = foundAli.username,
      phone = "",
      threadType = "chat",
      peerUid = foundAli.ownerUid,
      isRegisteredPeer = true
    )
    assertTrue(addRes.isSuccess)
    val contactIdOnNomi = addRes.getOrThrow()

    // Nomi sends two messages to Ali -> Ali receives them with unreadCount 1, then 2
    store.sendMessage("nomi", contactIdOnNomi, "me", "Hi Ali!", aiGenerated = false, incrementUnreadIfThem = true)
    store.sendMessage("nomi", contactIdOnNomi, "me", "Are you free?", aiGenerated = false, incrementUnreadIfThem = true)

    val aliContacts = store.observeContacts("ali_khan").first()
    val nomiThreadOnAli = aliContacts.firstOrNull { it.username == "nomi" }
    assertNotNull(nomiThreadOnAli)
    assertEquals(2L, nomiThreadOnAli?.unreadCount)
    assertEquals("Are you free?", nomiThreadOnAli?.lastMessageText)
    assertEquals(false, nomiThreadOnAli?.lastMessageFromMe)

    // Opening the thread clears unread count back to 0
    store.markThreadRead("ali_khan", nomiThreadOnAli!!.id)
    val afterRead = store.observeContacts("ali_khan").first().firstOrNull { it.id == nomiThreadOnAli.id }
    assertEquals(0L, afterRead?.unreadCount)

    // Incoming background message increments unread count from 0 -> 1
    store.sendMessage("ali_khan", nomiThreadOnAli.id, "them", "Ping!", aiGenerated = true, incrementUnreadIfThem = true)
    val afterIncoming = store.observeContacts("ali_khan").first().firstOrNull { it.id == nomiThreadOnAli.id }
    assertEquals(1L, afterIncoming?.unreadCount)

    // Updating Nomi's profile name propagates live to Ali's contact list
    store.updateProfile(usernameKey = "nomi", displayName = "Nomi Pro")
    val aliContactsAfterRename = store.observeContacts("ali_khan").first()
    val updatedNomiOnAli = aliContactsAfterRename.firstOrNull { it.id == nomiThreadOnAli.id }
    assertEquals("Nomi Pro", updatedNomiOnAli?.name)
  }

  @Test
  fun `new account starts with empty chat list and no dummy contacts or fake messages`() = runTest {
    val store = LocalAuthStore(context)
    val outcome = store.signUpUser("Nomi User", "nomi_clean", "2000-01-01", "pass123", "pass123")
    assertTrue(outcome is LocalAuthOutcome.Success)
    store.signUpUser("Friend User", "friend_user", "2000-01-01", "pass123", "pass123")
    store.setUserPresence("friend_user", false)

    val initialContacts = store.observeContacts("nomi_clean").first()
    assertTrue(initialContacts.isEmpty())

    val addRes = store.addContact(
      usernameKey = "nomi_clean",
      name = "Friend User",
      contactUsername = "@friend_user",
      phone = "",
      threadType = "chat"
    )
    assertTrue(addRes.isSuccess)
    val contactId = addRes.getOrThrow()

    val contacts = store.observeContacts("nomi_clean").first()
    assertEquals(1, contacts.size)
    assertEquals("", contacts.first().lastMessageText)
    assertFalse(contacts.first().online)

    val initialMsgs = store.observeMessages("nomi_clean", contactId).first()
    assertTrue(initialMsgs.isEmpty())
  }

  @Test
  fun `real presence toggling and incoming alerts work without auto-replies`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Alice User", "alice_u", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Bob User", "bob_u", "2000-01-01", "pass123", "pass123")

    // Alice goes offline -> Bob adds Alice -> Alice should appear Offline
    store.setUserPresence("alice_u", false)
    val addAliceOnBob = store.addContact(
      usernameKey = "bob_u",
      name = "Alice User",
      contactUsername = "alice_u",
      phone = "",
      threadType = "chat"
    ).getOrThrow()

    var aliceThread = store.observeContacts("bob_u").first().first { it.id == addAliceOnBob }
    assertFalse(aliceThread.online)

    // Alice opens app -> Online updates to true
    store.setUserPresence("alice_u", true)
    aliceThread = store.observeContacts("bob_u").first().first { it.id == addAliceOnBob }
    assertTrue(aliceThread.online)

    // Bob sends "Hi" -> Alice receives incoming alert & 1 message (no auto-reply on Bob's side)
    val alertDeferred = async { store.incomingAlerts.first() }
    store.sendMessage("bob_u", addAliceOnBob, "me", "Hi", aiGenerated = false)
    val alert: IncomingMessageAlert = alertDeferred.await()
    assertEquals("alice_u", alert.recipientUserId)
    assertEquals("Hi", alert.text)

    val bobMessages = store.observeMessages("bob_u", addAliceOnBob).first()
    assertEquals(1, bobMessages.size)
    assertEquals("me", bobMessages.first().sender)
  }

  @Test
  fun `mergeThreadWithDirectory updates peer displayName, ban status, and real online staleness`() {
    val nowMs = 1_000_000_000L
    val thread = ChatThread(
      id = "c_peer",
      userId = "uid_bob",
      name = "Old Alice",
      username = "alice",
      peerUid = "uid_alice",
      isRegisteredPeer = true,
      online = true,
      accountStatus = "active"
    )
    // Null directory entry forces offline (no fake online)
    assertFalse(NomiChatRepository.mergeThreadWithDirectory(thread, null, nowMs).online)

    val freshDirOnline = UserDirectoryEntry(
      id = "alice",
      ownerUid = "uid_alice",
      username = "alice",
      displayName = "Alice Live",
      online = true,
      accountStatus = "active",
      updatedAt = Timestamp((nowMs - 10_000L) / 1000L, 0)
    )
    val merged = NomiChatRepository.mergeThreadWithDirectory(thread, freshDirOnline, nowMs)
    assertEquals("Alice Live", merged.name)
    assertEquals("active", merged.accountStatus)
    assertTrue(merged.online)

    val renamedThread = thread.copy(name = "My Nickname", customRenamed = true)
    val mergedRenamed = NomiChatRepository.mergeThreadWithDirectory(renamedThread, freshDirOnline, nowMs)
    assertEquals("My Nickname", mergedRenamed.name)
    assertTrue(mergedRenamed.online)

    // Stale heartbeat (>150s old) drops back to Offline
    val staleDir = freshDirOnline.copy(
      updatedAt = Timestamp((nowMs - 200_000L) / 1000L, 0)
    )
    assertFalse(NomiChatRepository.mergeThreadWithDirectory(thread, staleDir, nowMs).online)

    val mismatchedDir = freshDirOnline.copy(ownerUid = "uid_other", displayName = "Spoofed")
    val untouched = NomiChatRepository.mergeThreadWithDirectory(thread, mismatchedDir, nowMs)
    assertEquals("Old Alice", untouched.name)
    assertFalse(untouched.online)

    val bannedDir = freshDirOnline.copy(accountStatus = "banned")
    val bannedMerged = NomiChatRepository.mergeThreadWithDirectory(thread, bannedDir, nowMs)
    assertEquals("banned", bannedMerged.accountStatus)
    assertFalse(bannedMerged.online)
  }

  @Test
  fun `network connectivity observer reads status safely`() {
    val observer = NetworkConnectivityObserver(context)
    val status = observer.currentStatus()
    assertTrue(status.transportLabel.isNotBlank())
  }

  @Test
  fun `added user syncs existing and updated peer profile photo and respects customRenamed`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Nomi One", "nomi_photo", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Sara Khan", "sara_k", "1999-09-09", "pass123", "pass123")

    val initialPhoto = "data:image/jpeg;base64,/9j/INITIAL_PHOTO"
    val updatedPhoto = "data:image/jpeg;base64,/9j/UPDATED_PHOTO"
    store.updateProfile(usernameKey = "sara_k", photoData = initialPhoto)

    val found = store.lookupUserByUsername("@sara_k").getOrNull()
    assertNotNull(found)
    assertEquals(initialPhoto, found?.photoData)

    val saraContactId = store.addContact(
      usernameKey = "nomi_photo",
      name = "",
      contactUsername = "@sara_k",
      phone = "",
      threadType = "chat"
    ).getOrThrow()

    var contacts = store.observeContacts("nomi_photo").first()
    val addedSara = contacts.first { it.id == saraContactId }
    assertEquals(initialPhoto, addedSara.photoData)

    // Rename chat on Nomi's side -> customRenamed = true
    store.renameChat("nomi_photo", saraContactId, "My Sister")

    // Sara updates her displayName & photo -> photo updates on Nomi's side, customRenamed is preserved
    store.updateProfile(usernameKey = "sara_k", displayName = "Sara Pro", photoData = updatedPhoto)
    contacts = store.observeContacts("nomi_photo").first()
    val updatedSara = contacts.first { it.id == saraContactId }
    assertEquals("My Sister", updatedSara.name)
    assertTrue(updatedSara.customRenamed)
    assertEquals(updatedPhoto, updatedSara.photoData)

    // Directory merge also propagates photoData while preserving customRenamed
    val nowMs = 1_000_000_000L
    val merged = NomiChatRepository.mergeThreadWithDirectory(
      addedSara.copy(name = "Custom Alias", customRenamed = true),
      UserDirectoryEntry(
        id = "sara_k",
        ownerUid = addedSara.peerUid,
        username = "sara_k",
        displayName = "Sara Directory",
        photoData = updatedPhoto,
        online = true,
        accountStatus = "active",
        updatedAt = Timestamp((nowMs - 5_000L) / 1000L, 0)
      ),
      nowMs
    )
    assertEquals("Custom Alias", merged.name)
    assertEquals(updatedPhoto, merged.photoData)
  }

  @Test
  fun `stories enforce 24-hour expiry and owner deletion`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Nomi Story", "nomi_story", "2000-01-01", "pass123", "pass123")
    val avatar = "data:image/jpeg;base64,/9j/AVATAR"
    store.updateProfile(usernameKey = "nomi_story", photoData = avatar)

    val nowMs = System.currentTimeMillis()
    val freshMedia = "data:image/jpeg;base64,/9j/FRESH_STORY"
    val expiredMedia = "data:image/jpeg;base64,/9j/EXPIRED_STORY"

    val freshId = store.postStory(
      usernameKey = "nomi_story",
      mediaData = freshMedia,
      caption = "Hello 24h",
      createdAtMs = nowMs
    ).getOrThrow()

    // Old story posted 25 hours ago -> already expired
    store.postStory(
      usernameKey = "nomi_story",
      mediaData = expiredMedia,
      caption = "Yesterday",
      createdAtMs = nowMs - (STORY_DURATION_MS + 3_600_000L)
    ).getOrThrow()

    val activeStories = store.observeStories("nomi_story").first()
    assertEquals(1, activeStories.size)
    val story = activeStories.first()
    assertEquals(freshId, story.id)
    assertEquals(STORY_DURATION_MS, story.expiresAtMs - story.createdAtMs)
    assertEquals(avatar, story.authorPhotoData)
    assertEquals("Hello 24h", story.caption)

    // Purge cleans expired records and delete removes active owner story
    store.purgeExpiredStories("nomi_story")
    assertEquals(1, store.observeStories("nomi_story").first().size)

    store.deleteStory("nomi_story", freshId)
    assertTrue(store.observeStories("nomi_story").first().isEmpty())
  }

  @Test
  fun `sending Hi cross-account delivers photo, preserves customRenamed, and emits alert`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Sender User", "sender_u", "2000-01-01", "pass123", "pass123")
    store.signUpUser("Receiver User", "receiver_u", "2000-01-01", "pass123", "pass123")

    val senderPhoto = "data:image/jpeg;base64,/9j/SENDER_AVATAR"
    store.updateProfile(usernameKey = "sender_u", photoData = senderPhoto)

    // Receiver adds sender and renames the chat locally
    val receiverContactId = store.addContact(
      usernameKey = "receiver_u",
      name = "",
      contactUsername = "@sender_u",
      phone = "",
      threadType = "chat"
    ).getOrThrow()
    store.renameChat("receiver_u", receiverContactId, "Office Friend")

    // Sender adds receiver and sends "Hi"
    val senderContactId = store.addContact(
      usernameKey = "sender_u",
      name = "",
      contactUsername = "@receiver_u",
      phone = "",
      threadType = "chat"
    ).getOrThrow()

    val alertDeferred = async { store.incomingAlerts.first() }
    store.sendMessage("sender_u", senderContactId, "me", "Hi", aiGenerated = false)
    val alert = alertDeferred.await()

    assertEquals("receiver_u", alert.recipientUserId)
    assertEquals("sender_u", alert.senderHandle)
    assertEquals("Hi", alert.text)

    val receiverThread = store.observeContacts("receiver_u").first().first { it.id == receiverContactId }
    assertEquals("Office Friend", receiverThread.name)
    assertTrue(receiverThread.customRenamed)
    assertEquals(senderPhoto, receiverThread.photoData)
    assertEquals(1L, receiverThread.unreadCount)
    assertEquals("Hi", receiverThread.lastMessageText)
  }
}




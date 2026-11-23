package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalAuthOutcome
import com.example.data.local.LocalAuthStore
import com.example.data.model.ChatThread
import com.example.data.model.ThemeMode
import com.example.data.model.UserDirectoryEntry
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.repository.NomiChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
  fun `lock block permanent delete and add by username persist across reloads`() = runTest {
    val store = LocalAuthStore(context)
    store.signUpUser("Alex Roe", "alexroe", "2000-01-01", "pass123", "pass123")

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

    store.toggleLockChat("alexroe", addedId, true)
    store.toggleBlockChat("alexroe", addedId, true)

    val reloadedStore = LocalAuthStore(context)
    val updatedThread = reloadedStore.observeContacts("alexroe").first().firstOrNull { it.id == addedId }
    assertNotNull(updatedThread)
    assertTrue(updatedThread?.locked == true)
    assertTrue(updatedThread?.blocked == true)

    reloadedStore.deleteChatPermanently("alexroe", addedId)
    val afterDelete = LocalAuthStore(context).observeContacts("alexroe").first()
    assertTrue(afterDelete.none { it.id == addedId })
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
  fun `mergeThreadWithDirectory updates peer displayName and ban status when owner matches`() {
    val thread = ChatThread(
      id = "c_peer",
      userId = "uid_bob",
      name = "Old Alice",
      username = "alice",
      peerUid = "uid_alice",
      isRegisteredPeer = true,
      accountStatus = "active"
    )
    val dirUpdate = UserDirectoryEntry(
      id = "alice",
      ownerUid = "uid_alice",
      username = "alice",
      displayName = "Alice Live",
      accountStatus = "active"
    )
    val merged = NomiChatRepository.mergeThreadWithDirectory(thread, dirUpdate)
    assertEquals("Alice Live", merged.name)
    assertEquals("active", merged.accountStatus)

    val mismatchedDir = dirUpdate.copy(ownerUid = "uid_other", displayName = "Spoofed")
    val untouched = NomiChatRepository.mergeThreadWithDirectory(thread, mismatchedDir)
    assertEquals("Old Alice", untouched.name)

    val bannedDir = dirUpdate.copy(accountStatus = "banned")
    val bannedMerged = NomiChatRepository.mergeThreadWithDirectory(thread, bannedDir)
    assertEquals("banned", bannedMerged.accountStatus)
  }

  @Test
  fun `network connectivity observer reads status safely`() {
    val observer = NetworkConnectivityObserver(context)
    val status = observer.currentStatus()
    assertTrue(status.transportLabel.isNotBlank())
  }
}




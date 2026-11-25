package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.SmartReplyRepository
import com.example.data.local.LocalAuthStore
import com.example.data.local.LocalNomiRepository
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.UiState
import com.example.data.network.NetworkConnectivityObserver
import com.example.data.network.NetworkStatus
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.NomiDataSource
import com.example.ui.screens.AddContactDialog
import com.example.ui.screens.ConversationScreen
import com.example.ui.screens.EditSingleFieldDialog
import com.example.ui.viewmodel.NomiChatViewModel
import com.example.ui.viewmodel.UsernameSearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkAndUsernameRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "nomi_auth_users.json").delete()
        File(context.filesDir, "nomi_retired_usernames.json").delete()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun configureActiveNetwork(
        transportType: Int?,
        validated: Boolean = true
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        if (transportType == null) {
            shadowCm.setActiveNetworkInfo(null)
            shadowCm.setNetworkCapabilities(cm.activeNetwork, null)
            return
        }
        val caps = ShadowNetworkCapabilities.newInstance()
        val shadowCaps = shadowOf(caps)
        shadowCaps.addTransportType(transportType)
        shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        if (validated) {
            shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            shadowCm.setNetworkCapabilities(activeNet, caps)
        }
    }

    @Test
    fun networkObserver_detectsWifiMobileDataAndOffline() = runTest(testDispatcher) {
        // Wi-Fi connected and validated
        configureActiveNetwork(NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        var probeCalled = false
        val wifiObserver = NetworkConnectivityObserver(context) {
            probeCalled = true
            true
        }
        val wifiStatus = wifiObserver.currentStatus()
        assertTrue(wifiStatus.isConnected)
        assertTrue(wifiStatus.wifiConnected)
        assertFalse(wifiStatus.mobileDataConnected)
        assertEquals("Wi-Fi", wifiStatus.transportLabel)
        assertTrue(wifiObserver.verifyInternetAccess())
        assertFalse(probeCalled)

        // Mobile SIM Data connected (unvalidated locally -> falls back to reachability probe)
        configureActiveNetwork(NetworkCapabilities.TRANSPORT_CELLULAR, validated = false)
        val simObserver = NetworkConnectivityObserver(context) {
            probeCalled = true
            true
        }
        val simStatus = simObserver.currentStatus()
        assertTrue(simStatus.isConnected)
        assertTrue(simStatus.mobileDataConnected)
        assertFalse(simStatus.wifiConnected)
        assertEquals("Mobile Data", simStatus.transportLabel)
        assertTrue(simObserver.verifyInternetAccess())
        assertTrue(probeCalled)

        // Neither Wi-Fi nor Mobile Data connected -> Offline
        val offlineObserver = object : NetworkConnectivityObserver(context) {
            override fun currentStatus(): NetworkStatus = NetworkStatus(
                isConnected = false,
                transportLabel = "Offline",
                wifiConnected = false,
                mobileDataConnected = false,
                hasValidatedInternet = false
            )
        }
        assertFalse(offlineObserver.currentStatus().isConnected)
        assertFalse(offlineObserver.verifyInternetAccess())
    }

    @Test
    fun sendMessage_hiBlockedWhenOfflineOrFirebaseDisconnected_andWorksWhenVerifiedOnline() = runTest(testDispatcher) {
        val store = LocalAuthStore(context)
        store.signUpUser("Alice One", "alice_net", "2000-01-01", "pass123", "pass123")
        store.signUpUser("Bob Two", "bob_net", "2000-01-01", "pass123", "pass123")
        val contactId = store.addContact("alice_net", "", "@bob_net", "", "chat").getOrThrow()

        var transportOnline = false
        var internetReachable = false
        var firebaseActive = false

        val fakeObserver = object : NetworkConnectivityObserver(context) {
            override fun currentStatus(): NetworkStatus = NetworkStatus(
                isConnected = transportOnline,
                transportLabel = if (transportOnline) "Wi-Fi" else "Offline",
                wifiConnected = transportOnline,
                mobileDataConnected = false,
                hasValidatedInternet = false
            )

            override suspend fun verifyInternetAccess(): Boolean {
                return transportOnline && internetReachable
            }
        }

        val innerRepo = LocalNomiRepository(store)
        val repoWithFirebaseGate = object : NomiDataSource by innerRepo {
            override suspend fun verifyActiveFirebaseConnection(): Boolean = firebaseActive
        }

        val vm = NomiChatViewModel(
            repository = repoWithFirebaseGate,
            smartReplyRepository = SmartReplyRepository(),
            preferencesRepository = UserPreferencesRepository(context),
            currentUserId = "alice_net",
            networkObserver = fakeObserver
        )
        advanceUntilIdle()
        vm.allThreadsState.first { it.any { c -> c.id == contactId } }
        vm.openConversation(contactId)
        vm.activeContact.first { it?.id == contactId }

        // 1. Offline: sending "Hi" is blocked immediately and triggers the exact pop-up
        vm.sendMessage("Hi")
        advanceUntilIdle()
        assertEquals(
            NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE,
            vm.popupMessage.value
        )
        assertTrue(store.observeMessages("alice_net", contactId).first().isEmpty())
        vm.clearPopup()

        // 2. Wi-Fi connected + internet OK, but Firebase not actively connected -> still blocked with pop-up
        transportOnline = true
        internetReachable = true
        firebaseActive = false
        vm.sendMessage("Hi")
        advanceUntilIdle()
        assertEquals(
            NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE,
            vm.popupMessage.value
        )
        assertTrue(store.observeMessages("alice_net", contactId).first().isEmpty())
        vm.clearPopup()

        // 3. Real internet + active Firebase verified -> sending "Hi" succeeds cleanly
        firebaseActive = true
        vm.sendMessage("Hi")
        advanceUntilIdle()
        val delivered = store.observeMessages("alice_net", contactId).first()
        assertEquals(1, delivered.size)
        assertEquals("Hi", delivered.first().text)
    }

    @Test
    fun viewModel_usernameChangeRejectsTakenOrRetiredNamesAndSearchReturnsAccountNotFound() = runTest(testDispatcher) {
        val store = LocalAuthStore(context)
        store.signUpUser("Nomi Owner", "nomi_old", "2000-01-01", "pass123", "pass123")
        store.signUpUser("Other User", "other_u", "2000-01-01", "pass123", "pass123")

        val fakeObserver = object : NetworkConnectivityObserver(context) {
            override fun currentStatus(): NetworkStatus = NetworkStatus(
                isConnected = true,
                transportLabel = "Mobile Data",
                mobileDataConnected = true,
                hasValidatedInternet = true
            )

            override suspend fun verifyInternetAccess(): Boolean = true
        }

        val vm = NomiChatViewModel(
            repository = LocalNomiRepository(store),
            smartReplyRepository = SmartReplyRepository(),
            preferencesRepository = UserPreferencesRepository(context),
            currentUserId = "nomi_old",
            networkObserver = fakeObserver
        )
        advanceUntilIdle()

        // Attempt to change to an already-taken username
        var closedDialog = false
        vm.updateProfileField(username = "other_u", onSuccess = { closedDialog = true })
        advanceUntilIdle()
        assertFalse(closedDialog)
        assertEquals("This username is already taken / already added.", vm.profileSaveError.value)

        // Change to a brand new username succeeds
        vm.updateProfileField(username = "nomi_new", onSuccess = { closedDialog = true })
        advanceUntilIdle()
        assertTrue(closedDialog)
        assertEquals(null, vm.profileSaveError.value)

        // Searching the old invalidated username returns "Account Not Found"
        val otherVm = NomiChatViewModel(
            repository = LocalNomiRepository(store),
            smartReplyRepository = SmartReplyRepository(),
            preferencesRepository = UserPreferencesRepository(context),
            currentUserId = "other_u",
            networkObserver = fakeObserver
        )
        advanceUntilIdle()
        otherVm.findUserByUsername("@nomi_old")
        advanceUntilIdle()
        val searchState = otherVm.usernameSearchState.value
        assertTrue(searchState is UsernameSearchState.NotFound)
        assertEquals("Account Not Found", (searchState as UsernameSearchState.NotFound).message)
    }

    @Test
    fun conversationScreen_preservesDraftWhenOfflineAndEditDialogShowsUsernameError() {
        var sentText: String? = null
        val contact = ChatThread(
            id = "c1",
            userId = "u1",
            name = "Bob Two",
            username = "bob_two",
            online = true
        )

        composeTestRule.setContent {
            MaterialTheme {
                ConversationScreen(
                    contact = contact,
                    messagesState = UiState.Success(emptyList<ChatMessage>()),
                    isContactTyping = false,
                    smartAiEnabled = false,
                    quickSuggestions = emptyList(),
                    isGeneratingSuggestions = false,
                    onToggleSmartAi = {},
                    onRequestSuggestions = {},
                    onDismissSuggestions = {},
                    onSendMessage = { sentText = it },
                    onReportContact = {},
                    onBack = {},
                    networkStatus = NetworkStatus(isConnected = false, transportLabel = "Offline")
                )
            }
        }

        composeTestRule.onNodeWithTag("message_input").performTextInput("Hi")
        composeTestRule.onNodeWithTag("send_message_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Hi", sentText)
        // Draft stays in composer because connection is offline
        composeTestRule.onNodeWithTag("message_input").assertTextContains("Hi")
    }

    @Test
    fun editSingleFieldDialog_displaysUsernameTakenErrorInline() {
        composeTestRule.setContent {
            MaterialTheme {
                EditSingleFieldDialog(
                    title = "Edit Username",
                    label = "Username",
                    initialValue = "nomi_new",
                    maxLength = 40,
                    sanitizeUsername = true,
                    errorText = "This username is already taken / already added.",
                    onDismiss = {},
                    onSave = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("edit_field_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("This username is already taken / already added.").assertIsDisplayed()
    }

    @Test
    fun addChat_blocksWhenOfflineOrFirebaseDisconnected_rejectsChangedUsername_andAcceptsActiveUsername() = runTest(testDispatcher) {
        val store = LocalAuthStore(context)
        store.signUpUser("Target User", "target_old", "2000-01-01", "pass123", "pass123")
        store.signUpUser("Searcher User", "searcher_net", "2000-01-01", "pass123", "pass123")

        var transportOnline = false
        var internetReachable = false
        var firebaseActive = false

        val gatedObserver = object : NetworkConnectivityObserver(context) {
            override fun currentStatus(): NetworkStatus = NetworkStatus(
                isConnected = transportOnline,
                transportLabel = if (transportOnline) "Wi-Fi" else "Offline",
                wifiConnected = transportOnline,
                mobileDataConnected = false,
                hasValidatedInternet = transportOnline && internetReachable
            )

            override suspend fun verifyInternetAccess(): Boolean {
                return transportOnline && internetReachable
            }
        }

        val innerRepo = LocalNomiRepository(store)
        val repoWithFirebaseGate = object : NomiDataSource by innerRepo {
            override suspend fun verifyActiveFirebaseConnection(): Boolean = firebaseActive
        }

        val vm = NomiChatViewModel(
            repository = repoWithFirebaseGate,
            smartReplyRepository = SmartReplyRepository(),
            preferencesRepository = UserPreferencesRepository(context),
            currentUserId = "searcher_net",
            networkObserver = gatedObserver
        )
        advanceUntilIdle()

        // 1. Offline: searching or adding a chat immediately blocks and shows offline pop-up
        var addSucceeded = false
        vm.findUserByUsername("@target_old")
        advanceUntilIdle()
        assertEquals(NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE, vm.popupMessage.value)
        vm.clearPopup()

        vm.addDirectChatByUsername("@target_old", onSuccess = { addSucceeded = true })
        advanceUntilIdle()
        assertFalse(addSucceeded)
        assertEquals(NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE, vm.popupMessage.value)
        assertTrue(store.observeContacts("searcher_net").first().isEmpty())
        vm.clearPopup()

        // 2. Wi-Fi & internet reachable, but Firebase live check returns false -> still blocks and adds nothing
        transportOnline = true
        internetReachable = true
        firebaseActive = false
        vm.addDirectChatByUsername("@target_old", onSuccess = { addSucceeded = true })
        advanceUntilIdle()
        assertFalse(addSucceeded)
        assertEquals(NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE, vm.popupMessage.value)
        assertTrue(store.observeContacts("searcher_net").first().isEmpty())
        vm.clearPopup()

        // 3. Target user changes username from target_old -> target_active
        store.updateProfile(usernameKey = "target_old", newUsername = "target_active").getOrThrow()

        // 4. Online + Firebase active, searching or adding changed/previous username -> "Account Not Found", zero chats added
        firebaseActive = true
        vm.findUserByUsername("@target_old")
        advanceUntilIdle()
        assertTrue(vm.usernameSearchState.value is UsernameSearchState.NotFound)
        assertEquals("Account Not Found", (vm.usernameSearchState.value as UsernameSearchState.NotFound).message)

        vm.addDirectChatByUsername("@target_old", onSuccess = { addSucceeded = true })
        advanceUntilIdle()
        assertFalse(addSucceeded)
        assertTrue(vm.usernameSearchState.value is UsernameSearchState.NotFound)
        assertEquals("Account Not Found", (vm.usernameSearchState.value as UsernameSearchState.NotFound).message)
        assertTrue(store.observeContacts("searcher_net").first().isEmpty())

        // 5. Online + Firebase active + valid active username -> accepted and chat added
        vm.addDirectChatByUsername("@target_active", onSuccess = { addSucceeded = true })
        advanceUntilIdle()
        assertTrue(addSucceeded)
        val contacts = store.observeContacts("searcher_net").first()
        assertEquals(1, contacts.size)
        assertEquals("target_active", contacts.first().username)
    }

    @Test
    fun addContactDialog_displaysAccountNotFoundAndOfflinePopupVisibly() {
        composeTestRule.setContent {
            MaterialTheme {
                AddContactDialog(
                    usernameSearchState = UsernameSearchState.NotFound("Account Not Found"),
                    offlinePopupMessage = NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE,
                    onDismiss = {},
                    onAddDirectChat = {},
                    onAddGroup = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("username_not_found_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Account Not Found").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_contact_offline_popup").assertIsDisplayed()
        composeTestRule.onNodeWithText(NetworkConnectivityObserver.NO_INTERNET_POPUP_MESSAGE).assertIsDisplayed()
    }
}

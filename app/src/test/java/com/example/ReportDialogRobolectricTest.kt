package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.ChatThread
import com.example.ui.screens.ReportAccountDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportDialogRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun reportAccountDialog_hidesBanThresholdAndReportCounter() {
        var confirmed = false
        val contact = ChatThread(
            id = "c1",
            userId = "u1",
            name = "Alex Mercer",
            username = "alexm",
            accountStatus = "active",
            reportCount = 7L,
            reportedBy = listOf("r1", "r2", "r3", "r4", "r5", "r6", "r7")
        )

        composeTestRule.setContent {
            MaterialTheme {
                ReportAccountDialog(
                    contact = contact,
                    onDismiss = {},
                    onConfirmReport = { confirmed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Report Alex Mercer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("report_dialog_message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Current reports", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("/ 10", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("10", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("automatically banned", substring = true, ignoreCase = true)
            .assertDoesNotExist()

        composeTestRule.onNodeWithTag("report_dialog_message")
            .assertTextContains("Our moderation team will review this account", substring = true)

        composeTestRule.onNodeWithTag("confirm_report_button").performClick()
        assertTrue(confirmed)
    }
}

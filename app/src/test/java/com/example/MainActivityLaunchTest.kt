package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityLaunchTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `app launches cleanly through onboarding and all main tabs`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Добро пожаловать в NetScope").assertIsDisplayed()

    composeTestRule.onNodeWithTag("onboarding_next_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("onboarding_next_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("onboarding_next_button").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5000) {
      composeTestRule.onAllNodesWithTag("dashboard_vpn_toggle_button").fetchSemanticsNodes().isNotEmpty()
    }

    // Now on Dashboard
    composeTestRule.onNodeWithTag("dashboard_vpn_toggle_button").assertIsDisplayed()

    // Navigate to Injector and verify Browser + Overlay/Sources tabs
    composeTestRule.onNodeWithTag("nav_injector").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("injector_url_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("injector_tab_settings").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("injector_overlay_toggle_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("injector_master_switch").performScrollTo().assertIsDisplayed()

    // Navigate through remaining tabs
    composeTestRule.onNodeWithTag("nav_traffic").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("traffic_search_input").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_apiclient").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("api_send_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_settings").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_install_ca_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_open_history_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("history_search_input").assertIsDisplayed()
  }
}

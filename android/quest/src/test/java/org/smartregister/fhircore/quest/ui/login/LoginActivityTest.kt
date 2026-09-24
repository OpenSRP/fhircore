/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ui.login

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.material.ExperimentalMaterialApi
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowIntent
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.quest.app.fakes.Faker
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.ui.main.AppMainActivity
import org.smartregister.fhircore.quest.ui.pin.PinLoginActivity
import org.smartregister.p2p.P2PLibrary

@ExperimentalCoroutinesApi
@HiltAndroidTest
class LoginActivityTest : RobolectricTest() {

  @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)

  @BindValue
  val configurationRegistry: ConfigurationRegistry = spyk(Faker.buildTestConfigurationRegistry())

  @BindValue
  val secureSharedPreference =
    spyk(SecureSharedPreference(ApplicationProvider.getApplicationContext()))
  private val loginActivityController =
    Robolectric.buildActivity(Faker.TestLoginActivity::class.java)
  private lateinit var loginActivity: LoginActivity
  val context = InstrumentationRegistry.getInstrumentation().targetContext!!

  @Before
  fun setUp() {
    hiltRule.inject()
    ApplicationProvider.getApplicationContext<Context>().apply { setTheme(R.style.AppTheme) }
    every { secureSharedPreference.retrieveSessionPin() } returns null
    every { secureSharedPreference.retrieveSessionUsername() } returns
      Faker.authCredentials.username
    loginActivity = loginActivityController.create().resume().get()
  }

  override fun tearDown() {
    super.tearDown()
    loginActivityController.destroy()
  }

  @Test
  fun testForgotPasswordLoadsContact() {
    val launchDialPadObserver =
      Observer<String?> { dialPadUri ->
        if (dialPadUri != null) {
          Assert.assertEquals("1234567890", dialPadUri)
        }
      }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    try {
      loginActivity.loginViewModel.launchDialPad.observeForever(launchDialPadObserver)
      loginActivity.loginViewModel.forgotPassword(context)
    } finally {
      loginActivity.loginViewModel.launchDialPad.removeObserver(launchDialPadObserver)
    }
  }

  @Test
  fun testLaunchDialPadStartsDialIntentWithCorrectPhoneNumber() {
    val phoneNumber = "1234567890"
    loginActivity.launchDialPad(phoneNumber)
    val resultIntent = shadowOf(loginActivity).nextStartedActivity
    Assert.assertNotNull(resultIntent)
    Assert.assertEquals(Intent.ACTION_DIAL, resultIntent.action)
    Assert.assertEquals(phoneNumber, resultIntent.data?.schemeSpecificPart.toString())
  }

  @Test
  fun testNavigateToScreenShouldLaunchPinLoginWithSetup() {
    val loginActivityController =
      Robolectric.buildActivity(Faker.TestLoginActivityInActivePin::class.java)
    val loginActivity: LoginActivity = loginActivityController.create().resume().get()

    // Return a null session pin, pin login is enabled by default
    every { secureSharedPreference.retrieveSessionPin() } returns null

    mockkObject(P2PLibrary)
    every { P2PLibrary.init(any()) } returns mockk()

    loginActivity.loginViewModel.updateNavigateHome(true)

    val resultIntent = shadowOf(loginActivity).nextStartedActivity
    Assert.assertNotNull(resultIntent)
    Assert.assertNotNull(resultIntent.extras)
    Assert.assertTrue(resultIntent.extras!!.containsKey(PinLoginActivity.PIN_SETUP))
    Assert.assertTrue(resultIntent.extras!!.getBoolean(PinLoginActivity.PIN_SETUP))

    val shadowIntent: ShadowIntent = shadowOf(resultIntent)
    Assert.assertEquals(PinLoginActivity::class.java, shadowIntent.intentClass)

    unmockkObject(P2PLibrary)
  }

  @Test
  fun testNavigateToScreenShouldInvokeNavigateToPinLoginWithActivePinAndOffline() {
    val resultIntent = shadowOf(loginActivity).nextStartedActivity
    Assert.assertNotNull(resultIntent)
    Assert.assertNotNull(resultIntent.extras)
    Assert.assertTrue(resultIntent.extras!!.containsKey(PinLoginActivity.PIN_SETUP))
    Assert.assertFalse(resultIntent.extras!!.getBoolean(PinLoginActivity.PIN_SETUP))

    val shadowIntent: ShadowIntent = shadowOf(resultIntent)
    Assert.assertEquals(PinLoginActivity::class.java, shadowIntent.intentClass)
  }

  @Test
  @Ignore("Weird: Cannot set session pin")
  fun testNavigateToScreenShouldLaunchPinLoginWithoutSetup() = runBlocking {
    // Return a session pin, login with pin is enabled by default
    val onSavedPinMock = mockk<() -> Unit>(relaxed = true)
    secureSharedPreference.saveSessionPin(pin = "1234".toCharArray(), onSavedPin = onSavedPinMock)

    verify { onSavedPinMock.invoke() }

    every { secureSharedPreference.retrieveSessionPin() } returns "1234"

    loginActivity.loginViewModel.updateNavigateHome(true)

    val resultIntent = shadowOf(loginActivity).nextStartedActivity
    Assert.assertNotNull(resultIntent)
    Assert.assertNotNull(resultIntent.extras)
    Assert.assertTrue(resultIntent.extras!!.containsKey(PinLoginActivity.PIN_SETUP))
    Assert.assertFalse(resultIntent.extras!!.getBoolean(PinLoginActivity.PIN_SETUP))

    val shadowIntent: ShadowIntent = shadowOf(resultIntent)
    Assert.assertEquals(PinLoginActivity::class.java, shadowIntent.intentClass)
  }

  @Test
  fun testNavigateToScreenShouldResumeSessionWhenRefreshTokenIsActiveAndPinDisabled() {
    mockkObject(P2PLibrary)
    every { P2PLibrary.init(any()) } returns mockk()

    val controller = Robolectric.buildActivity(Faker.TestLoginActivityWithoutPin::class.java)
    val activity = controller.create().resume().get()
    shadowOf(Looper.getMainLooper()).idle()

    Assert.assertTrue(activity.loginViewModel.navigateToHome.value!!)

    controller.destroy()
    unmockkObject(P2PLibrary)
  }

  @Test
  fun testNavigateToScreenShouldNotResumeSessionWhenUserLoggedOut() {
    val controller = Robolectric.buildActivity(Faker.TestLoginActivityLoggedOut::class.java)
    val activity = controller.create().resume().get()
    shadowOf(Looper.getMainLooper()).idle()

    // The refresh token is still active, but an explicit logout must not resume the session
    Assert.assertFalse(activity.loginViewModel.navigateToHome.value!!)

    controller.destroy()
  }

  @OptIn(ExperimentalMaterialApi::class)
  @Test
  fun testNavigateToScreenShouldLaunchHomeScreen() {
    // Mock p2p Library then un mock it at the end of test
    mockkObject(P2PLibrary)
    every { P2PLibrary.init(any()) } returns mockk()

    loginActivity.navigateToHome()

    val resultIntent = shadowOf(loginActivity).nextStartedActivity
    Assert.assertNotNull(resultIntent)
    val shadowIntent: ShadowIntent = shadowOf(resultIntent)
    Assert.assertEquals(AppMainActivity::class.java, shadowIntent.intentClass)

    unmockkObject(P2PLibrary)
  }
}

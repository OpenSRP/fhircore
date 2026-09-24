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

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import retrofit2.HttpException

@ExperimentalCoroutinesApi
@HiltAndroidTest
class AccountAuthenticatorTest : RobolectricTest() {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @Inject lateinit var configService: ConfigService

  @BindValue var secureSharedPreference: SecureSharedPreference = mockk()

  @BindValue var tokenAuthenticator: TokenAuthenticator = mockk()

  @Inject lateinit var dispatcherProvider: DispatcherProvider
  private lateinit var accountAuthenticator: AccountAuthenticator
  private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
  private val authTokenType = "authTokenType"
  private val accountManager: AccountManager = mockk()

  @Before
  fun setUp() {
    hiltRule.inject()
    accountAuthenticator =
      spyk(
        AccountAuthenticator(
          context = context,
          accountManager = accountManager,
          tokenAuthenticator = tokenAuthenticator,
        ),
      )
  }

  @Test
  fun testEditPropertiesShouldReturnEmptyBundle() {
    Assert.assertTrue(accountAuthenticator.editProperties(null, null).isEmpty)
  }

  @Test
  fun testAddAccountShouldReturnRelevantBundle() {
    val accountType = "accountType"
    val accountBundle =
      accountAuthenticator.addAccount(mockk(), accountType, authTokenType, arrayOf(), bundleOf())

    Assert.assertNotNull(accountBundle)
    Assert.assertTrue(accountBundle.containsKey(AccountManager.KEY_INTENT))

    val intent = accountBundle.get(AccountManager.KEY_INTENT) as Intent

    Assert.assertEquals(accountType, intent.getStringExtra(AccountAuthenticator.ACCOUNT_TYPE))
    Assert.assertEquals(authTokenType, intent.getStringExtra(TokenAuthenticator.AUTH_TOKEN_TYPE))
  }

  @Test
  fun testConfirmCredentialsShouldReturnEmptyBundle() {
    Assert.assertTrue(accountAuthenticator.confirmCredentials(mockk(), mockk(), bundleOf()).isEmpty)
  }

  @Test
  fun testGetAuthTokenWithoutRefreshToken() {
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    val account = spyk(Account("newAccName", "newAccType"))
    every { accountManager.peekAuthToken(account, authTokenType) } returns ""
    val refreshToken = "refreshToken"
    every { accountManager.getPassword(account) } returns refreshToken
    every { accountManager.setPassword(account, any()) } just runs

    every { tokenAuthenticator.refreshToken(account, refreshToken) } returns ""

    val authToken = accountAuthenticator.getAuthToken(mockk(), account, authTokenType, bundleOf())
    val parcelable = authToken.get(AccountManager.KEY_INTENT) as Intent

    Assert.assertNotNull(authToken)
    Assert.assertNotNull(parcelable)
    Assert.assertTrue(parcelable.hasExtra(AccountAuthenticator.ACCOUNT_TYPE))
    Assert.assertTrue(parcelable.hasExtra(TokenAuthenticator.AUTH_TOKEN_TYPE))
  }

  @Test
  fun testGetAuthTokenWithRefreshToken() {
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    val account = spyk(Account("newAccName", "newAccType"))
    every { accountManager.peekAuthToken(account, authTokenType) } returns ""

    val refreshToken = "refreshToken"
    every { accountManager.getPassword(account) } returns refreshToken
    every { accountManager.setPassword(account, any()) } just runs
    every { tokenAuthenticator.refreshToken(account, refreshToken) } returns "newAccessToken"

    val authTokenBundle: Bundle =
      accountAuthenticator.getAuthToken(null, account, authTokenType, bundleOf())

    Assert.assertNotNull(authTokenBundle)
    Assert.assertTrue(authTokenBundle.containsKey(AccountManager.KEY_AUTHTOKEN))
    Assert.assertEquals("newAccessToken", authTokenBundle.getString(AccountManager.KEY_AUTHTOKEN))
  }

  @Test
  fun testGetBundleWithoutAuthInfoWhenCaughtHttpException() {
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    val account = spyk(Account("newAccName", "newAccType"))
    every { accountManager.peekAuthToken(account, authTokenType) } returns ""

    val refreshToken = "refreshToken"
    every { accountManager.getPassword(account) } returns refreshToken
    every { tokenAuthenticator.refreshToken(account, refreshToken) } throws
      HttpException(
        mockk {
          every { code() } returns 0
          every { message() } returns ""
        },
      )

    val authTokenBundle: Bundle =
      accountAuthenticator.getAuthToken(null, account, authTokenType, bundleOf())

    Assert.assertNotNull(authTokenBundle)
    Assert.assertFalse(authTokenBundle.containsKey(AccountManager.KEY_AUTHTOKEN))
  }

  @Test
  fun testGetBundleWithoutAuthInfoWhenCaughtUnknownHostException() {
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    val account = spyk(Account("newAccName", "newAccType"))
    every { accountManager.peekAuthToken(account, authTokenType) } returns ""

    val refreshToken = "refreshToken"
    every { accountManager.getPassword(account) } returns refreshToken
    every { tokenAuthenticator.refreshToken(account, refreshToken) } throws UnknownHostException()

    val authTokenBundle: Bundle =
      accountAuthenticator.getAuthToken(null, account, authTokenType, bundleOf())

    Assert.assertNotNull(authTokenBundle)
    Assert.assertFalse(authTokenBundle.containsKey(AccountManager.KEY_AUTHTOKEN))
  }

  @Test
  fun testGetBundleWithoutAuthInfoWhenRefreshTokenFailsWithRuntimeException() {
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    val account = spyk(Account("newAccName", "newAccType"))
    every { accountManager.peekAuthToken(account, authTokenType) } returns ""

    val refreshToken = "refreshToken"
    every { accountManager.getPassword(account) } returns refreshToken
    every { tokenAuthenticator.refreshToken(account, refreshToken) } throws RuntimeException()

    accountAuthenticator.getAuthToken(null, account, authTokenType, bundleOf()).also {
      Assert.assertNotNull(it)
      Assert.assertFalse(it.containsKey(AccountManager.KEY_AUTHTOKEN))
    }
  }

  @Test
  fun testGetAuthTokenLabel() {
    val authTokenLabel = "auth_token_label"
    Assert.assertEquals(
      authTokenLabel.uppercase(),
      accountAuthenticator.getAuthTokenLabel(authTokenLabel),
    )
  }

  @Test
  fun testUpdateCredentialsShouldReturnEmptyBundle() {
    Assert.assertTrue(
      accountAuthenticator.updateCredentials(mockk(), mockk(), authTokenType, bundleOf()).isEmpty,
    )
  }

  @Test
  fun testHasFeaturesShouldReturnEmptyBundle() {
    Assert.assertNotNull(accountAuthenticator.hasFeatures(mockk(), mockk(), arrayOf()))
  }

  @Test
  fun testThatLogoutCallsTokenAuthenticatorLogout() {
    every { tokenAuthenticator.logout() } returns Result.success(true)
    val onLogout = {}
    accountAuthenticator.logout(onLogout)
    verify { tokenAuthenticator.logout() }
  }

  @Test
  fun testThatLogoutInvokesOnLogoutWhenRemoteLogoutIsUnsuccessful() {
    every { tokenAuthenticator.logout() } returns Result.success(false)
    val onLogout = mockk<() -> Unit>(relaxed = true)
    accountAuthenticator.logout(onLogout)
    verify { onLogout.invoke() }
  }

  @Test
  fun testThatLogoutInvokesOnLogoutWhenRemoteLogoutFails() {
    every { tokenAuthenticator.logout() } returns
      Result.failure(UnknownHostException("Unable to reach the server"))
    val onLogout = mockk<() -> Unit>(relaxed = true)
    accountAuthenticator.logout(onLogout)
    verify { onLogout.invoke() }
  }

  @Test
  fun testValidateLoginCredentials() {
    every { tokenAuthenticator.validateSavedLoginCredentials(any(), any()) } returns true
    Assert.assertTrue(accountAuthenticator.validateLoginCredentials("doe", "pswd".toCharArray()))
  }

  @Test
  fun testThatInvalidateSessionCallsTokenAuthenticatorInvalidateSession() {
    every { tokenAuthenticator.invalidateSession(any()) } just runs
    val onSessionInvalidated = {}
    accountAuthenticator.invalidateSession(onSessionInvalidated)
    verify { tokenAuthenticator.invalidateSession(onSessionInvalidated) }
  }
}

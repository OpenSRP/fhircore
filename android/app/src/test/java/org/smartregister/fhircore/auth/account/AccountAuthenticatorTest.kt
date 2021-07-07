/*
 * Copyright 2021 Ona Systems, Inc
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

package org.smartregister.fhircore.auth.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.smartregister.fhircore.RobolectricTest
import org.smartregister.fhircore.auth.OAuthResponse
import org.smartregister.fhircore.shadow.FhirApplicationShadow

@Config(shadows = [FhirApplicationShadow::class])
class AccountAuthenticatorTest : RobolectricTest() {
  private lateinit var accountAuthenticator: AccountAuthenticator
  private lateinit var accountHelper: AccountHelper
  private lateinit var accountManager: AccountManager
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun setUp() {
    accountHelper = mockk()
    accountManager = mockk()

    accountAuthenticator = AccountAuthenticator(context)
    accountAuthenticator.accountHelper = accountHelper
    accountAuthenticator.accountManager = accountManager
  }

  @Test
  fun testAddAccountShouldReturnIntentWithCorrectExtras() {
    val result =
      accountAuthenticator.addAccount(
        mockk(),
        AccountConfig.ACCOUNT_TYPE,
        AccountConfig.AUTH_TOKEN_TYPE,
        null,
        bundleOf()
      )

    val intent = result.getParcelable<Intent>(AccountManager.KEY_INTENT)
    val extras = intent!!.extras!!

    assertEquals(AccountConfig.AUTH_HANDLER_ACTIVITY.name, intent.component?.className)
    assertEquals(AccountConfig.ACCOUNT_TYPE, extras.getString(AccountManager.KEY_ACCOUNT_TYPE))
    assertEquals(AccountConfig.AUTH_TOKEN_TYPE, extras.getString(AccountConfig.KEY_AUTH_TOKEN_TYPE))
  }

  @Test
  fun testGetAuthTokenShouldReturnBundleWithCorrectAccountData() {
    val oauth = OAuthResponse()
    oauth.accessToken = "valid access token"
    oauth.refreshToken = "valid refresh token"
    oauth.expiresIn = 1444444444
    oauth.refreshExpiresIn = 1444444444

    every { accountManager.peekAuthToken(any(), any()) } returns null
    every { accountManager.getPassword(any()) } returns "some refresh token"
    every { accountHelper.refreshToken("some refresh token") } returns oauth

    val result =
      accountAuthenticator.getAuthToken(
        mockk(),
        Account("testuser", AccountConfig.ACCOUNT_TYPE),
        AccountConfig.AUTH_TOKEN_TYPE,
        bundleOf()
      )

    assertEquals("testuser", result.getString(AccountManager.KEY_ACCOUNT_NAME))
    assertEquals(AccountConfig.ACCOUNT_TYPE, result.getString(AccountManager.KEY_ACCOUNT_TYPE))
    assertEquals(oauth.accessToken, result.getString(AccountManager.KEY_AUTHTOKEN))
  }

  @Test
  fun testEditPropertiesShouldReturnValidBundle() {
    assertNotNull(accountAuthenticator.editProperties(mockk(), ""))
  }

  @Test
  fun testConfirmCredentialsShouldReturnValidBundle() {
    assertNotNull(accountAuthenticator.confirmCredentials(mockk(), mockk(), mockk()))
  }

  @Test
  fun testGetAuthTokenLabelShouldCovertTextToUpperCase() {
    assertEquals("DEMO_TYPE", accountAuthenticator.getAuthTokenLabel("demo_type"))
  }

  @Test
  fun testUpdateCredentialsVerifyIntentProperties() {

    val account = Account("testuser", AccountConfig.ACCOUNT_TYPE)
    val bundle =
      accountAuthenticator.updateCredentials(
        mockk(),
        account,
        AccountConfig.AUTH_TOKEN_TYPE,
        bundleOf()
      )

    val intent = bundle.getParcelable(AccountManager.KEY_INTENT) as Intent?

    assertNotNull(intent)
    assertNotNull(intent?.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE))
    assertEquals(account.name, intent?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
    assertEquals(account.type, intent?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE))
  }

  @Test
  fun testHasFeaturesShouldReturnValidBundle() {
    val bundle = accountAuthenticator.hasFeatures(mockk(), mockk(), arrayOf())

    assertNotNull(bundle)
    assertFalse(bundle.getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
  }
}

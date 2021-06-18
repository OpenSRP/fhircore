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
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.smartregister.fhircore.auth.OAuthResponse
import org.smartregister.fhircore.robolectric.FhircoreTestRunner
import org.smartregister.fhircore.shadow.FhirApplicationShadow

@RunWith(FhircoreTestRunner::class)
@Config(shadows = [FhirApplicationShadow::class])
class AccountAuthenticatorTest {
  private lateinit var accountAuthenticator: AccountAuthenticator
  private lateinit var accountHelper: AccountHelper
  private lateinit var accountManager: AccountManager
  private val context = ApplicationProvider.getApplicationContext<Context>()

  lateinit var captor: CapturingSlot<Map<String, String>>

  @Before
  fun setUp() {
    accountHelper = mockk()
    accountManager = mockk()

    accountAuthenticator = AccountAuthenticator(context)
    accountAuthenticator.accountHelper = accountHelper
    accountAuthenticator.accountManager = accountManager
  }

  @Test
  fun `verify add account`() {
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
  fun `verify get auth token`() {
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
}
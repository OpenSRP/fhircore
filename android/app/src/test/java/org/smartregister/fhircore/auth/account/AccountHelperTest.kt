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
import android.accounts.AccountManagerCallback
import android.accounts.NetworkErrorException
import android.content.Context
import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import okhttp3.ResponseBody
import org.junit.Assert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.robolectric.annotation.Config
import org.smartregister.fhircore.BuildConfig
import org.smartregister.fhircore.RobolectricTest
import org.smartregister.fhircore.api.OAuthService
import org.smartregister.fhircore.auth.OAuthResponse
import org.smartregister.fhircore.auth.secure.FakeKeyStore
import org.smartregister.fhircore.shadow.FhirApplicationShadow
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

@Config(shadows = [FhirApplicationShadow::class])
class AccountHelperTest : RobolectricTest() {
  private var mockOauthService: OAuthService? = null
  private lateinit var accountHelper: AccountHelper

  private val context = ApplicationProvider.getApplicationContext<Context>()

  lateinit var captor: CapturingSlot<Map<String, String>>

  @Before
  fun setUp() {
    mockOauthService = mockk()

    mockkObject(OAuthService.Companion)

    every { OAuthService.create(any()) } returns mockOauthService!!

    accountHelper = AccountHelper(context)

    captor = slot()
  }

  @Test
  fun testGetUserInfoShouldReturnNonNull() {
    every { mockOauthService?.userInfo() } returns mockk()
    Assert.assertNotNull(accountHelper.getUserInfo())
  }

  @Test(expected = NetworkErrorException::class)
  fun testFetchTokenShouldSendCorrectData() {

    every { mockOauthService?.fetchToken(capture(captor)) } returns mockk()

    accountHelper.fetchToken("testuser", "testpass".toCharArray())

    assertTrue(captor.captured.get("client_id").equals(BuildConfig.OAUTH_CIENT_ID))
    assertTrue(captor.captured.get("client_secret").equals(BuildConfig.OAUTH_CLIENT_SECRET))
    assertTrue(captor.captured.get("grant_type").equals("password"))
    assertTrue(captor.captured.get("username").equals("testuser"))
    assertTrue(captor.captured.get("password").equals("testpass"))

    every { mockOauthService?.fetchToken(any()) } throws NetworkErrorException()
    accountHelper.fetchToken("testuser", "testpass".toCharArray())
  }

  @Test
  fun testRefreshTokenShouldSendCorrectData() {
    val mockCall = mockk<Call<OAuthResponse>>()

    every { mockOauthService?.fetchToken(capture(captor)) } returns mockCall

    val mockResponse = mockk<Response<OAuthResponse>>()

    every { mockCall.execute() } returns mockResponse

    every {
      hint(OAuthResponse::class)
      mockResponse.body()
    } returns mockk()

    accountHelper.refreshToken("my test refresh token")

    assertTrue(captor.captured.get("client_id").equals(BuildConfig.OAUTH_CIENT_ID))
    assertTrue(captor.captured.get("client_secret").equals(BuildConfig.OAUTH_CLIENT_SECRET))
    assertTrue(captor.captured.get("grant_type").equals("refresh_token"))
    assertTrue(captor.captured.get("refresh_token").equals("my test refresh token"))
  }

  @Test
  fun testIsSessionActiveShouldReturnFalseWithNullToken() {
    val result = accountHelper.isSessionActive(null)

    assertFalse(result)
  }

  @Test
  fun testIsSessionActiveShouldReturnFalseWithInvalidToken() {
    val result = accountHelper.isSessionActive("my invalid token")

    assertFalse(result)
  }

  @Test
  fun testIsSessionActiveShouldReturnFalseWithExpiredToken() {
    val result =
      accountHelper.isSessionActive(
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
          "eyJpc3MiOiJ0b3B0YWwuY29tIiwiZXhwIjoxNDI2NDIwODAwLCJodHRwOi8vdG9wdGFsLmNvbS9qd3RfY2xhaW1zL2lzX2FkbWluIjp0cnVlLCJjb21wYW55IjoiVG9wdGFsIiwiYXdlc29tZSI6dHJ1ZX0." +
          "yRQYnWzskCZUxPwaQupWkiUzKELZ49eM7oWxAQK_ZXw"
      )

    assertFalse(result)
  }

  @Test
  fun testAddAuthenticatedAccountShouldAddAccountWithCorrectData() {
    val oauth = OAuthResponse()
    oauth.accessToken = "valid access token"
    oauth.refreshToken = "valid refresh token"
    oauth.expiresIn = 1444444444
    oauth.refreshExpiresIn = 1444444444

    val accountManager = spyk<AccountManager>()

    every { accountManager.notifyAccountAuthenticated(any()) } returns true

    accountHelper.addAuthenticatedAccount(accountManager, Response.success(oauth), "testuser")

    val account = Account("testuser", AccountConfig.ACCOUNT_TYPE)

    verify { accountManager.addAccountExplicitly(account, oauth.refreshToken, null) }

    verify {
      accountManager.setAuthToken(account, AccountConfig.AUTH_TOKEN_TYPE, oauth.accessToken)
    }

    verify { accountManager.setPassword(account, oauth.refreshToken) }
  }

  @Test
  fun testLoadAccountShouldDoNothingWithNonExistentAccount() {
    val accountManager = spyk<AccountManager>()

    every { accountManager.getAccountsByType(AccountConfig.ACCOUNT_TYPE) } returns arrayOf()

    accountHelper.loadAccount(
      accountManager,
      "testuser",
      AccountManagerCallback { Timber.i("RES {}", it.result) },
      Handler()
    )

    verify(exactly = 0) { accountManager.getAuthToken(any(), any(), any(), true, any(), any()) }
  }

  @Test
  fun testLoadAccountShouldLoadAccountWithExistingAccount() {
    val accountManager = spyk<AccountManager>()

    every { accountManager.getAccountsByType(AccountConfig.ACCOUNT_TYPE) } returns
      arrayOf(Account("testuser", AccountConfig.ACCOUNT_TYPE))

    accountHelper.loadAccount(
      accountManager,
      "testuser",
      AccountManagerCallback { Timber.i("RES {}", it.result) },
      Handler()
    )

    verify(exactly = 1) { accountManager.getAuthToken(any(), any(), any(), true, any(), any()) }
  }

  @Test
  fun testLogout() {

    val accountManager = spyk<AccountManager>()
    val response = mockk<Call<ResponseBody>>()
    val slot = slot<Callback<ResponseBody>>()

    every { accountManager.getAccountsByType(AccountConfig.ACCOUNT_TYPE) } returns
      arrayOf(Account("testuser", AccountConfig.ACCOUNT_TYPE))

    every { accountManager.clearPassword(any()) } returns Unit
    every { accountManager.getPassword(any()) } returns ""
    every { mockOauthService?.logout(any(), any(), any()) } returns response
    every { response.enqueue(capture(slot)) } answers { slot.captured.onResponse(mockk(), mockk()) }

    accountHelper.logout(accountManager)
    verify(exactly = 1) { accountManager.clearPassword(any()) }
  }

  companion object {
    @JvmStatic
    @BeforeClass
    fun beforeClass() {
      FakeKeyStore.setup
    }
  }
}

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

package org.smartregister.fhircore.engine.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerFuture
import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import com.auth0.jwt.exceptions.JWTDecodeException
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.internal.http.RealResponseBody
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.data.remote.auth.OAuthService
import org.smartregister.fhircore.engine.data.remote.model.response.OAuthResponse
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator.Companion.AUTH_TOKEN_TYPE
import org.smartregister.fhircore.engine.robolectric.RobolectricTest
import org.smartregister.fhircore.engine.rule.CoroutineTestRule
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.toPasswordHash
import retrofit2.HttpException
import retrofit2.Response

@HiltAndroidTest
class TokenAuthenticatorTest : RobolectricTest() {

  @get:Rule val hiltRule = HiltAndroidRule(this)

  @ExperimentalCoroutinesApi @get:Rule val coroutineRule = CoroutineTestRule()

  @Inject lateinit var secureSharedPreference: SecureSharedPreference

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @Inject lateinit var configService: ConfigService
  private val oAuthService: OAuthService = mockk()
  private lateinit var tokenAuthenticator: TokenAuthenticator
  private val accountManager = mockk<AccountManager>()
  private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
  private val sampleUsername = "demo"

  @Before
  @ExperimentalCoroutinesApi
  fun setUp() {
    hiltRule.inject()
    tokenAuthenticator =
      spyk(
        TokenAuthenticator(
          secureSharedPreference = secureSharedPreference,
          configService = configService,
          oAuthService = oAuthService,
          dispatcherProvider = dispatcherProvider,
          accountManager = accountManager,
          context = context,
        ),
      )
  }

  @After
  fun tearDown() {
    secureSharedPreference.deleteCredentials()
  }

  @Test
  fun testIsTokenActiveWithNullToken() {
    Assert.assertFalse(tokenAuthenticator.isTokenActive(null))
  }

  @Test
  @Throws(JWTDecodeException::class)
  fun testIsTokenActiveWithExpiredJwtToken() {
    Assert.assertFalse(tokenAuthenticator.isTokenActive("expired-token"))
  }

  @Test
  fun testGetAccessTokenShouldReturnValidAccessToken() {
    val account = Account(sampleUsername, PROVIDER)
    every { tokenAuthenticator.findAccount() } returns account
    every { tokenAuthenticator.isTokenActive(any()) } returns true
    val accessToken = "gibberishaccesstoken"
    every { accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) } returns accessToken
    Assert.assertEquals(accessToken, tokenAuthenticator.getAccessToken())
  }

  @Test
  fun testGetAccessTokenShouldInvalidateExpiredToken() {
    val account = Account(sampleUsername, PROVIDER)
    val accessToken = "gibberishaccesstoken"
    val accountManagerFuture = mockk<AccountManagerFuture<Bundle>>()
    every { tokenAuthenticator.findAccount() } returns account
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    every { accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) } returns accessToken
    every { accountManager.invalidateAuthToken(account.type, accessToken) } just runs
    every { accountManagerFuture.result } returns bundleOf()
    every {
      accountManager.getAuthToken(account, AUTH_TOKEN_TYPE, any(), true, any(), any())
    } returns accountManagerFuture

    tokenAuthenticator.getAccessToken()

    verifyOrder {
      accountManager.invalidateAuthToken(account.type, accessToken)
      accountManager.getAuthToken(account, AUTH_TOKEN_TYPE, any(), true, any(), any())
    }
  }

  @Test
  fun testGetAccessTokenShouldReturnEmptyStringIfAccountNull() {
    every { tokenAuthenticator.findAccount() } returns null
    Assert.assertEquals("", tokenAuthenticator.getAccessToken())
  }

  @Test
  fun testGetAccessTokenShouldCatchOperationCanceledAndIOAndAuthenticatorExceptions() {
    val account = Account(sampleUsername, PROVIDER)
    val accountManagerFutureBundle = mockk<AccountManagerFuture<Bundle>>()
    every { tokenAuthenticator.findAccount() } returns account
    every { tokenAuthenticator.isTokenActive(any()) } returns false
    val accessToken = "gibberishaccesstoken"
    every { accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) } returns accessToken
    every { accountManager.invalidateAuthToken(account.type, accessToken) } just runs
    every { accountManagerFutureBundle.result } throws OperationCanceledException()
    every {
      accountManager.getAuthToken(account, AUTH_TOKEN_TYPE, any<Bundle>(), true, any(), any())
    } returns accountManagerFutureBundle
    Assert.assertEquals(accessToken, tokenAuthenticator.getAccessToken())
    every { accountManagerFutureBundle.result } throws IOException()
    every {
      accountManager.getAuthToken(account, AUTH_TOKEN_TYPE, any<Bundle>(), true, any(), any())
    } returns accountManagerFutureBundle
    Assert.assertEquals(accessToken, tokenAuthenticator.getAccessToken())
    every { accountManagerFutureBundle.result } throws AuthenticatorException()
    every {
      accountManager.getAuthToken(account, AUTH_TOKEN_TYPE, any<Bundle>(), true, any(), any())
    } returns accountManagerFutureBundle
    Assert.assertEquals(accessToken, tokenAuthenticator.getAccessToken())
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testFetchTokenShouldRetrieveNewTokenAndCreateAccount() {
    val token = "goodToken"
    val refreshToken = "refreshToken"
    val username = sampleUsername
    val password = charArrayOf('P', '4', '5', '5', 'W', '4', '0')
    var passwordSalt = byteArrayOf(-128, 100, 112, 127)

    val secureSharedPreference = spyk(secureSharedPreference)
    val tokenAuthenticator =
      spyk(
        TokenAuthenticator(
          secureSharedPreference = secureSharedPreference,
          configService = configService,
          oAuthService = oAuthService,
          dispatcherProvider = dispatcherProvider,
          accountManager = accountManager,
          context = context,
        ),
      )

    val oAuthResponse =
      OAuthResponse(
        accessToken = token,
        refreshToken = refreshToken,
        tokenType = "",
        expiresIn = 3600,
        scope = SCOPE,
      )
    coEvery { oAuthService.fetchToken(any()) } returns oAuthResponse

    every { secureSharedPreference.get256RandomBytes() } returns passwordSalt
    every { accountManager.accounts } returns arrayOf()

    val accountSlot = slot<Account>()
    val tokenSlot = slot<String>()

    every { accountManager.addAccountExplicitly(capture(accountSlot), any(), null) } returns true
    every { accountManager.setAuthToken(any(), any(), capture(tokenSlot)) } just runs

    runTest {
      tokenAuthenticator.fetchAccessToken(username, password)
      Assert.assertEquals(username, accountSlot.captured.name)
      Assert.assertEquals(token, tokenSlot.captured)
    }

    // Credentials saved
    val credentials = secureSharedPreference.retrieveCredentials()
    Assert.assertNotNull(credentials)
    Assert.assertTrue(username.contentEquals(credentials?.username))

    Assert.assertEquals(
      charArrayOf('P', '4', '5', '5', 'W', '4', '0').toPasswordHash(passwordSalt),
      credentials?.passwordHash,
    )
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun testFetchTokenShouldSetPasswordAndAuthTokenForExistingAccount() = runTest {
    val testAPKApplicationId = "org.smartregister.fhircore.engine.test"
    val account = Account(sampleUsername, testAPKApplicationId)
    val password = charArrayOf('P', '4', '5', '5', 'W', '4', '0')
    val token = "goodToken"
    val refreshToken = "refreshToken"

    val oAuthResponse =
      OAuthResponse(
        accessToken = token,
        refreshToken = refreshToken,
        tokenType = "",
        expiresIn = 3600,
        scope = SCOPE,
      )
    coEvery { oAuthService.fetchToken(any()) } returns oAuthResponse
    every { accountManager.accounts } returns arrayOf(account)
    every { accountManager.setPassword(account, oAuthResponse.refreshToken) } just runs
    every { accountManager.setAuthToken(account, AUTH_TOKEN_TYPE, oAuthResponse.accessToken) } just
      runs

    tokenAuthenticator.fetchAccessToken(sampleUsername, password)

    verifyOrder {
      accountManager.setPassword(account, oAuthResponse.refreshToken)
      accountManager.setAuthToken(account, AUTH_TOKEN_TYPE, oAuthResponse.accessToken)
    }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testFetchTokenShouldShouldCatchHttpAndUnknownHostAndSSLHandshakeExceptions() {
    val username = sampleUsername
    val password = charArrayOf('P', '4', '5', '5', 'W', '4', '0')

    val httpException = HttpException(Response.success(null))

    coEvery { oAuthService.fetchToken(any()) }.throws(httpException)

    runTest {
      var result = tokenAuthenticator.fetchAccessToken(username, password)
      Assert.assertEquals(Result.failure<HttpException>(httpException), result)
    }

    val unknownHostException = UnknownHostException()

    coEvery { oAuthService.fetchToken(any()) }.throws(unknownHostException)

    runTest {
      var result = tokenAuthenticator.fetchAccessToken(username, password)
      Assert.assertEquals(Result.failure<UnknownHostException>(unknownHostException), result)
    }

    val sslHandshakeException = SSLHandshakeException("reason")

    coEvery { oAuthService.fetchToken(any()) }.throws(sslHandshakeException)

    runTest {
      var result = tokenAuthenticator.fetchAccessToken(username, password)
      Assert.assertEquals(Result.failure<SSLHandshakeException>(sslHandshakeException), result)
    }
  }

  @Test
  fun testLogout() {
    val account = Account(sampleUsername, PROVIDER)
    val refreshToken = "gibberishaccesstoken"
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.getPassword(account) } returns refreshToken

    coEvery { oAuthService.logout(any(), any()) } returns
      Response.success(200, mockk<RealResponseBody>())

    every { accountManager.invalidateAuthToken(account.type, any()) } just runs
    every { accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) } returns "oldToken"
    val result = tokenAuthenticator.logout()
    Assert.assertTrue(result.isSuccess)
  }

  @Test
  fun testLogoutShouldShouldCatchHttpAndUnknownHostExceptions() {
    val account = Account(sampleUsername, PROVIDER)
    val refreshToken = "gibberishaccesstoken"
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.getPassword(account) } returns refreshToken

    val httpException = HttpException(Response.success(null))

    coEvery { oAuthService.logout(any(), any()) }.throws(httpException)

    var result = tokenAuthenticator.logout()
    Assert.assertEquals(Result.failure<HttpException>(httpException), result)

    val unknownHostException = UnknownHostException()

    coEvery { oAuthService.logout(any(), any()) }.throws(unknownHostException)

    result = tokenAuthenticator.logout()
    Assert.assertEquals(Result.failure<UnknownHostException>(unknownHostException), result)
  }

  @Test
  fun testLogoutShouldCatchSslHandshakeException() {
    val account = Account(sampleUsername, PROVIDER)
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.getPassword(account) } returns "gibberishaccesstoken"

    val sslHandshakeException = SSLHandshakeException("Handshake failed")

    coEvery { oAuthService.logout(any(), any()) }.throws(sslHandshakeException)

    val result = tokenAuthenticator.logout()
    Assert.assertEquals(Result.failure<SSLHandshakeException>(sslHandshakeException), result)
  }

  @Test
  fun testRefreshTokenShouldReturnToken() {
    val account = Account(sampleUsername, PROVIDER)
    val accessToken = "soRefreshingNewToken"
    val oAuthResponse =
      OAuthResponse(
        accessToken = accessToken,
        refreshToken = "soRefreshingRefreshToken",
        tokenType = "",
        expiresIn = 3600,
        scope = SCOPE,
      )
    coEvery { oAuthService.fetchToken(any()) } returns oAuthResponse
    every { accountManager.setPassword(account, any()) } just runs

    val currentRefreshToken = "oldRefreshToken"
    val newAccessToken = tokenAuthenticator.refreshToken(account, currentRefreshToken)
    Assert.assertNotNull(newAccessToken)
    Assert.assertEquals(accessToken, newAccessToken)
    verify { accountManager.setPassword(eq(account), oAuthResponse.refreshToken) }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun testValidateSavedLoginCredentialsShouldReturnTrue() {
    val passwd = "P455W40"
    val passwordSalt = byteArrayOf(-128, 100, 112, 127)

    val secureSharedPreference = spyk(secureSharedPreference)
    every { secureSharedPreference.get256RandomBytes() } returns passwordSalt
    secureSharedPreference.saveCredentials(sampleUsername, passwd.toCharArray())
    val tokenAuthenticator =
      spyk(
        TokenAuthenticator(
          secureSharedPreference = secureSharedPreference,
          configService = configService,
          oAuthService = oAuthService,
          dispatcherProvider = dispatcherProvider,
          accountManager = accountManager,
          context = context,
        ),
      )

    val result =
      tokenAuthenticator.validateSavedLoginCredentials(sampleUsername, passwd.toCharArray())
    Assert.assertTrue(result)
  }

  @Test
  fun testFindAccountShouldReturnAnAccount() {
    secureSharedPreference.saveCredentials(sampleUsername, "sirikali".toCharArray())
    val account = Account(sampleUsername, PROVIDER)
    every { accountManager.getAccountsByType(any()) } returns arrayOf(account)
    val resultAccount = tokenAuthenticator.findAccount()
    Assert.assertNotNull(resultAccount)
    Assert.assertEquals(account.name, resultAccount?.name)
    Assert.assertEquals(account.type, resultAccount?.type)
  }

  @Test
  fun testSessionActiveWithActiveToken() {
    val account = Account(sampleUsername, PROVIDER)
    val token = "anotherToken"
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) } returns token
    every { tokenAuthenticator.isTokenActive(any()) } returns true

    Assert.assertTrue(tokenAuthenticator.sessionActive())
  }

  @Test
  fun testSessionActiveWithInActiveToken() {
    val account = Account(sampleUsername, PROVIDER)
    val token = "anotherToken"
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) } returns token
    every { tokenAuthenticator.isTokenActive(any()) } returns false

    Assert.assertFalse(tokenAuthenticator.sessionActive())
  }

  @Test
  fun testInvalidateSessionShouldInvalidateToken() {
    val account = Account(sampleUsername, PROVIDER)
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.invalidateAuthToken(account.type, AUTH_TOKEN_TYPE) } just runs
    every { accountManager.removeAccountExplicitly(account) } returns true

    val onSessionInvalidated = {}
    tokenAuthenticator.invalidateSession(onSessionInvalidated)

    verifyOrder {
      accountManager.invalidateAuthToken(account.type, AUTH_TOKEN_TYPE)
      accountManager.removeAccountExplicitly(account)
      onSessionInvalidated()
    }
  }

  @Test
  fun testsCurrentRefreshTokenActiveWithInActiveToken() {
    val account = Account(sampleUsername, PROVIDER)
    val token =
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6Ik1hcnRpbiBOZGVnd2EiLCJpYXQiOjE1MTYyMzkwMjIsImV4cCI6MzMyNDI4OTMyMzF9.sYnHKVkXrku7X4X92zsidrZeKyd6nbOvsM5qgck3kiA"
    every { tokenAuthenticator.findAccount() } returns account
    every { accountManager.getPassword(account) } returns token

    Assert.assertTrue(tokenAuthenticator.isCurrentRefreshTokenActive())
  }

  @Test
  fun testIsCurrentRefreshTokenActiveWithNoAccountReturnsFalse() {
    every { tokenAuthenticator.findAccount() } returns null

    Assert.assertFalse(tokenAuthenticator.isCurrentRefreshTokenActive())
  }

  companion object {
    private const val SCOPE = "openid"
    private const val PROVIDER = "provider"
  }
}

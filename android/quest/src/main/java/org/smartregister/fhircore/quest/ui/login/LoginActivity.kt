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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.data.local.ContentCache
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.p2p.dao.P2PReceiverTransferDao
import org.smartregister.fhircore.engine.p2p.dao.P2PSenderTransferDao
import org.smartregister.fhircore.engine.sync.AppSyncWorker
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.extension.applyWindowInsetListener
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.engine.util.extension.launchActivityWithNoBackStackHistory
import org.smartregister.fhircore.quest.ui.main.AppMainActivity
import org.smartregister.fhircore.quest.ui.pin.PinLoginActivity
import org.smartregister.p2p.P2PLibrary

@AndroidEntryPoint
open class LoginActivity : BaseMultiLanguageActivity() {

  @Inject lateinit var p2pSenderTransferDao: P2PSenderTransferDao

  @Inject lateinit var p2pReceiverTransferDao: P2PReceiverTransferDao

  @Inject lateinit var contentCache: ContentCache

  @Inject lateinit var workManager: WorkManager
  val loginViewModel by viewModels<LoginViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    this.applyWindowInsetListener()
    loginViewModel.launchDialPad.observe(
      this,
    ) { phone ->
      if (!phone.isNullOrBlank()) {
        startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$phone") })
      }
    }
    // Cancel sync background job to get new auth token; login required, refresh token expired
    val cancelBackgroundSync =
      intent.extras?.getBoolean(TokenAuthenticator.CANCEL_BACKGROUND_SYNC, false) ?: false
    if (cancelBackgroundSync) workManager.cancelAllWorkByTag(AppSyncWorker::class.java.name)

    navigateToScreen()
    setContent { AppTheme { LoginScreen(loginViewModel = loginViewModel) } }
  }

  @VisibleForTesting
  fun navigateToScreen() {
    loginViewModel.apply {
      val loginActivity = this@LoginActivity
      val isPinEnabled = pinEnabled()
      val hasActivePin = pinActive()

      if (isPinEnabled && hasActivePin) {
        if (
          (loginActivity.deviceOnline() && loginActivity.isRefreshTokenActive()) ||
            !loginActivity.deviceOnline()
        ) {
          navigateToPinLogin(launchSetup = false)
        }
      } else {
        // The refresh token outlives a logout, so skip the resume when the user explicitly logged
        // out. Otherwise the home screen is restored without any credentials being entered.
        if (!loginActivity.userLoggedOut() && loginActivity.isRefreshTokenActive()) {
          updateNavigateHome(true)
        }
      }
      viewModelScope.launch { contentCache.invalidate() }
      navigateToHome.observe(loginActivity) { launchHomeScreen ->
        if (launchHomeScreen) {
          downloadNowWorkflowConfigs()
          if (isPinEnabled && !hasActivePin) {
            navigateToPinLogin(launchSetup = true)
          } else {
            loginActivity.navigateToHome()
          }
        }
      }
      launchDialPad.observe(loginActivity) { if (!it.isNullOrBlank()) launchDialPad(it) }
    }
  }

  @VisibleForTesting open fun pinEnabled() = loginViewModel.isPinEnabled()

  @VisibleForTesting
  open fun pinActive() = !loginViewModel.secureSharedPreference.retrieveSessionPin().isNullOrEmpty()

  @VisibleForTesting
  open fun isRefreshTokenActive() = loginViewModel.tokenAuthenticator.isCurrentRefreshTokenActive()

  @VisibleForTesting
  open fun userLoggedOut() =
    loginViewModel.sharedPreferences.read(SharedPreferenceKey.USER_LOGGED_OUT.name, false)

  @VisibleForTesting open fun deviceOnline() = isDeviceOnline()

  fun navigateToHome() {
    startActivity(Intent(this, AppMainActivity::class.java))
    // Initialize P2P after login only when username is provided then finish activity
    val username = loginViewModel.secureSharedPreference.retrieveSessionUsername()
    if (!username.isNullOrEmpty()) {
      P2PLibrary.init(
        P2PLibrary.Options(
          context = applicationContext,
          dbPassphrase = username,
          username = username,
          senderTransferDao = p2pSenderTransferDao,
          receiverTransferDao = p2pReceiverTransferDao,
        ),
      )
    }
    finish()
  }

  private fun navigateToPinLogin(launchSetup: Boolean = false) {
    this.launchActivityWithNoBackStackHistory<PinLoginActivity>(
      bundle = bundleOf(Pair(PinLoginActivity.PIN_SETUP, launchSetup)),
    )
  }

  fun launchDialPad(phone: String) {
    startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$phone") })
  }
}

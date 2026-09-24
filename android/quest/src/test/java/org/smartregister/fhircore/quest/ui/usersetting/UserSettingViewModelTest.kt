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

package org.smartregister.fhircore.quest.ui.usersetting

import android.content.Context
import android.os.Looper
import android.widget.Toast
import androidx.navigation.NavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.Sync
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import javax.inject.Inject
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.configuration.app.SettingsOptions
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceDataSource
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.datastore.PreferenceDataStore
import org.smartregister.fhircore.engine.domain.model.Language
import org.smartregister.fhircore.engine.sync.SyncBroadcaster
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.engine.util.extension.launchActivityWithNoBackStackHistory
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.engine.util.extension.spaceByUppercase
import org.smartregister.fhircore.engine.util.test.HiltActivityForTest
import org.smartregister.fhircore.quest.app.AppConfigService
import org.smartregister.fhircore.quest.app.fakes.Faker
import org.smartregister.fhircore.quest.navigation.MainNavigationScreen
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.ui.login.AccountAuthenticator
import org.smartregister.fhircore.quest.ui.login.LoginActivity

@HiltAndroidTest
class UserSettingViewModelTest : RobolectricTest() {

  @get:Rule var hiltRule = HiltAndroidRule(this)

  @BindValue var configurationRegistry = Faker.buildTestConfigurationRegistry()

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @Inject lateinit var preferenceDataStore: PreferenceDataStore
  lateinit var fhirEngine: FhirEngine
  private var sharedPreferencesHelper: SharedPreferencesHelper
  private var configService: ConfigService
  private lateinit var syncBroadcaster: SyncBroadcaster
  private lateinit var userSettingViewModel: UserSettingViewModel
  private lateinit var accountAuthenticator: AccountAuthenticator
  private lateinit var secureSharedPreference: SecureSharedPreference
  private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
  private val resourceService: FhirResourceService = mockk()
  private val workManager = mockk<WorkManager>(relaxed = true, relaxUnitFun = true)
  private var fhirResourceDataSource: FhirResourceDataSource
  private val sync = mockk<Sync>(relaxed = true)
  private val navController = mockk<NavController>(relaxUnitFun = true)

  init {
    sharedPreferencesHelper = SharedPreferencesHelper(context = context, gson = mockk())
    configService = AppConfigService(context = context)
    fhirResourceDataSource = spyk(FhirResourceDataSource(resourceService))
  }

  @Before
  @kotlinx.coroutines.ExperimentalCoroutinesApi
  fun setUp() {
    hiltRule.inject()
    accountAuthenticator = mockk(relaxUnitFun = true)
    secureSharedPreference = mockk()
    sharedPreferencesHelper = mockk()
    fhirEngine = mockk(relaxUnitFun = true)
    syncBroadcaster =
      spyk(
        SyncBroadcaster(
          configurationRegistry,
          fhirEngine = mockk(),
          dispatcherProvider = dispatcherProvider,
          syncListenerManager = mockk(relaxed = true),
          workManager = workManager,
          context = context,
        ),
      )

    userSettingViewModel =
      spyk(
        UserSettingViewModel(
          fhirEngine = fhirEngine,
          syncBroadcaster = syncBroadcaster,
          accountAuthenticator = accountAuthenticator,
          secureSharedPreference = secureSharedPreference,
          sharedPreferencesHelper = sharedPreferencesHelper,
          preferenceDataStore = preferenceDataStore,
          configurationRegistry = configurationRegistry,
          workManager = workManager,
          dispatcherProvider = dispatcherProvider,
        ),
      )
  }

  @Test
  fun testRunSyncWhenDeviceIsOnline() {
    coEvery { syncBroadcaster.runOneTimeSync() } returns Unit
    userSettingViewModel.onEvent(UserSettingsEvent.SyncData(context))
    coVerify(exactly = 1) { syncBroadcaster.runOneTimeSync() }
  }

  @Test
  fun testDoNotRunSyncWhenDeviceIsOffline() {
    mockkStatic(Context::isDeviceOnline)

    val context = mockk<Context>(relaxed = true) { every { isDeviceOnline() } returns false }

    coEvery { syncBroadcaster.runOneTimeSync() } returns Unit

    userSettingViewModel.onEvent(UserSettingsEvent.SyncData(context))
    coVerify(exactly = 0) { syncBroadcaster.runOneTimeSync() }

    val errorMessage = context.getString(R.string.sync_failed)
    coVerify { context.showToast(errorMessage, Toast.LENGTH_LONG) }

    unmockkStatic(Context::isDeviceOnline)
  }

  @Test
  fun testRetrieveUsernameShouldReturnDemo() {
    every { secureSharedPreference.retrieveSessionUsername() } returns "demo"

    Assert.assertEquals("demo", userSettingViewModel.retrieveUsername())

    verify { secureSharedPreference.retrieveSessionUsername() }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun testLogoutUserShouldCallAuthLogoutService() {
    val activity = mockk<HiltActivityForTest>(relaxed = true)
    every { activity.isDeviceOnline() } returns true
    every { activity.launchActivityWithNoBackStackHistory<LoginActivity>() } just runs
    val userSettingsEvent = UserSettingsEvent.Logout(activity)
    every { accountAuthenticator.logout(any()) } just runs

    userSettingViewModel.onEvent(userSettingsEvent)

    verify(exactly = 1) { accountAuthenticator.logout(any()) }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun testLogoutWhenOfflineShouldRecordThatUserLoggedOut() {
    val activity = mockk<HiltActivityForTest>(relaxed = true)
    every { activity.isDeviceOnline() } returns false
    every { sharedPreferencesHelper.write(any(), any<Boolean>()) } just runs

    userSettingViewModel.onEvent(UserSettingsEvent.Logout(activity))
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    // The login screen must ask for credentials instead of resuming the session
    verify { sharedPreferencesHelper.write(SharedPreferenceKey.USER_LOGGED_OUT.name, true) }
  }

  @Test
  fun allowSwitchingLanguagesShouldReturnFalseWhenSwitchLanguagesOptionIsNotSetAndMultipleLanguagesAreConfigured() {
    val languages = listOf(Language("es", "Spanish"), Language("en", "English"))
    userSettingViewModel = spyk(userSettingViewModel)

    every { userSettingViewModel.enableMenuOption(SettingsOptions.SWITCH_LANGUAGES) } returns false
    every { userSettingViewModel.languages } returns languages

    Assert.assertFalse(userSettingViewModel.allowSwitchingLanguages())
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun allowSwitchingLanguagesShouldReturnTrueWhenSwitchLanguagesOptionIsSetAndMultipleLanguagesAreConfigured() {
    val languages = listOf(Language("es", "Spanish"), Language("en", "English"))
    userSettingViewModel = spyk(userSettingViewModel)

    every { userSettingViewModel.enableMenuOption(SettingsOptions.SWITCH_LANGUAGES) } returns true
    every { userSettingViewModel.languages } returns languages

    Assert.assertTrue(userSettingViewModel.allowSwitchingLanguages())
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun allowSwitchingLanguagesShouldReturnFalseWhenSwitchLanguagesOptionIsSetAndOnlyOneLanguageIsConfigured() {
    val languages = listOf(Language("en", "English"))
    userSettingViewModel = spyk(userSettingViewModel)

    every { userSettingViewModel.enableMenuOption(SettingsOptions.SWITCH_LANGUAGES) } returns true
    every { userSettingViewModel.languages } returns languages

    Assert.assertFalse(userSettingViewModel.allowSwitchingLanguages())
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun loadSelectedLanguage() {
    every { sharedPreferencesHelper.read(SharedPreferenceKey.LANG.name, "en") } returns "fr"
    Assert.assertEquals("French", userSettingViewModel.loadSelectedLanguage())
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    verify { sharedPreferencesHelper.read(SharedPreferenceKey.LANG.name, "en") }
  }

  @Test
  fun setLanguageShouldCallSharedPreferencesHelperWriteWithSelectedLanguageTagAndPostValue() {
    val language = Language("es", "Spanish")
    val userSettingsEvent = UserSettingsEvent.SwitchLanguage(language, context)

    every { sharedPreferencesHelper.write(any(), any<String>()) } just runs

    userSettingViewModel.onEvent(userSettingsEvent)

    Shadows.shadowOf(Looper.getMainLooper()).idle()

    verify { sharedPreferencesHelper.write(SharedPreferenceKey.LANG.name, "es") }

    Assert.assertTrue(configurationRegistry.configCacheMap.isEmpty())
  }

  @Test
  fun languagesLazyPropertyShouldRunFetchLanguagesAndReturnConfiguredLanguages() {
    val languages = userSettingViewModel.languages

    Assert.assertTrue(languages.isNotEmpty())
    val language = languages.find { it.displayName == "English" }
    Assert.assertEquals("English", language?.displayName)
    Assert.assertEquals("en", language?.tag)
  }

  @Test
  fun testShowResetDatabaseConfirmationDialogShouldUpdateFlagCorrectly() {
    Assert.assertEquals(false, userSettingViewModel.showDBResetConfirmationDialog.value)

    val userSettingsEvent = UserSettingsEvent.ShowResetDatabaseConfirmationDialog(true)

    userSettingViewModel.onEvent(userSettingsEvent)

    ShadowLooper.idleMainLooper()
    Assert.assertEquals(true, userSettingViewModel.showDBResetConfirmationDialog.value)
  }

  @Test
  fun testResetDatabaseFlagEventShouldInvokeResetDatabaseMethod() {
    val userSettingViewModelSpy = spyk(userSettingViewModel)
    every { userSettingViewModelSpy.resetAppData(any()) } just runs

    val userSettingsEvent = UserSettingsEvent.ResetDatabaseFlag(true, context)

    userSettingViewModelSpy.onEvent(userSettingsEvent)

    verify { userSettingViewModelSpy.resetAppData(any()) }
  }

  @Test
  fun testShowLoaderViewShouldUpdateShowProgressFlagCorrectly() {
    Assert.assertEquals(false, userSettingViewModel.progressBarState.value?.first)

    val userSettingsEvent = UserSettingsEvent.ShowLoaderView(true, R.string.resetting_app)

    userSettingViewModel.onEvent(userSettingsEvent)

    ShadowLooper.idleMainLooper()
    Assert.assertEquals(true, userSettingViewModel.progressBarState.value?.first)
    Assert.assertEquals(R.string.resetting_app, userSettingViewModel.progressBarState.value?.second)
  }

  @Test
  @kotlinx.coroutines.ExperimentalCoroutinesApi
  fun testResetAppDataShouldClearEverything() = runTest {
    userSettingViewModel.resetAppData(context)
    every { accountAuthenticator.invalidateSession(any()) } just runs
    verify { workManager.cancelAllWork() }
    coVerify { fhirEngine.clearDatabase() }
    verify { accountAuthenticator.invalidateSession(any()) }
  }

  @Test
  fun testShowInsightScreen() {
    val userSettingViewModelSpy = spyk(userSettingViewModel)
    val showInsightScreenEvent = UserSettingsEvent.ShowInsightsScreen(navController)
    userSettingViewModelSpy.onEvent(showInsightScreenEvent)
    verify { navController.navigate(MainNavigationScreen.Insight.route) }
  }

  @Test
  @kotlinx.coroutines.ExperimentalCoroutinesApi
  fun testFetchUnsyncedResources() = runTest {
    coEvery {
      fhirEngine
        .getUnsyncedLocalChanges()
        .distinctBy { it.resourceId }
        .groupingBy { it.resourceType.spaceByUppercase() }
        .eachCount()
        .map { it.key to it.value }
    } returns listOf("Patient" to 10, "Encounters" to 5, "Observations" to 20)
  }
}

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

package org.smartregister.fhircore.quest.ui.main

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import io.sentry.android.navigation.SentryNavigationListener
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.LocationLogOptions
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.datastore.ProtoDataStore
import org.smartregister.fhircore.engine.domain.model.LauncherType
import org.smartregister.fhircore.engine.rulesengine.services.LocationCoordinate
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.sync.SyncState
import org.smartregister.fhircore.engine.ui.base.AlertDialogButton
import org.smartregister.fhircore.engine.ui.base.AlertDialogue
import org.smartregister.fhircore.engine.ui.base.AlertIntent
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.extension.launchActivityWithNoBackStackHistory
import org.smartregister.fhircore.engine.util.extension.parcelable
import org.smartregister.fhircore.engine.util.extension.serializable
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.engine.util.location.LocationUtils
import org.smartregister.fhircore.engine.util.location.PermissionUtils
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.login.LoginActivity
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.shared.ActivityOnResultType
import org.smartregister.fhircore.quest.ui.shared.ON_RESULT_TYPE
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import timber.log.Timber

@AndroidEntryPoint
open class AppMainActivity : BaseMultiLanguageActivity(), QuestionnaireHandler, OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var protoDataStore: ProtoDataStore

  @Inject lateinit var eventBus: EventBus

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @Inject lateinit var tokenAuthenticator: TokenAuthenticator

  val appMainViewModel by viewModels<AppMainViewModel>()
  private val sentryNavListener =
    SentryNavigationListener(enableNavigationBreadcrumbs = true, enableNavigationTracing = true)

  private val locationPermissionLauncher: ActivityResultLauncher<Array<String>> =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      permissions: Map<String, Boolean> ->
      PermissionUtils.getLocationPermissionLauncher(
        permissions = permissions,
        onFineLocationPermissionGranted = { fetchLocation() },
        onCoarseLocationPermissionGranted = { fetchLocation() },
        onLocationPermissionDenied = {
          showToast(
            getString(R.string.location_permissions_denied),
            Toast.LENGTH_SHORT,
          )
        },
      )
    }

  private lateinit var fusedLocationClient: FusedLocationProviderClient

  override val startForResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      activityResult: ActivityResult ->
      val onResultType = activityResult.data?.extras?.getString(ON_RESULT_TYPE)
      lifecycleScope.launch {
        if (
          activityResult.resultCode == Activity.RESULT_OK &&
            !onResultType.isNullOrBlank() &&
            ActivityOnResultType.valueOf(onResultType) == ActivityOnResultType.QUESTIONNAIRE
        ) {
          onSubmitQuestionnaire(activityResult)
        }

        // Check if session expired while questionnaire was open
        if (!tokenAuthenticator.isCurrentRefreshTokenActive()) {
          delay(3000) // Let user see data was saved
          showSessionExpiredDialog()
        }
      }
    }

  /**
   * When the NavHostFragment is inflated using FragmentContainerView, if you attempt to use
   * findNavController in the onCreate() of the Activity, the nav controller cannot be found. This
   * is because when the fragment is inflated in the constructor of FragmentContainerView, the
   * fragmentManager is in the INITIALIZING state, and therefore the added fragment only goes up to
   * initializing. For the nav controller to be properly set, the fragment view needs to be created
   * and onViewCreated() needs to be dispatched, which does not happen until the ACTIVITY_CREATED
   * state. As a workaround retrieve the navController from the [NavHostFragment]
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    val navController =
      (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
    val launcherType =
      appMainViewModel.applicationConfiguration.navigationStartDestination.launcherType

    val graph =
      runBlocking(dispatcherProvider.io()) {
        navController.navInflater.inflate(R.navigation.application_nav_graph).apply {
          val startDestination =
            when (launcherType) {
              LauncherType.MAP -> R.id.geoWidgetLauncherFragment
              LauncherType.REGISTER -> R.id.registerFragment
            }
          setStartDestination(startDestination)
        }
      }
    findViewById<View>(R.id.mainScreenProgressBar).apply { visibility = View.GONE }
    findViewById<View>(R.id.mainScreenProgressBarText).apply { visibility = View.GONE }
    navController.setGraph(graph, appMainViewModel.getStartDestinationArgs())
    appMainViewModel.retrieveAppMainUiState()
    lifecycleScope.launch { appMainViewModel.schedulePeriodicJobs(this@AppMainActivity) }
    setupLocationServices()
    overrideOnBackPressListener()
  }

  override fun onResume() {
    super.onResume()
    findNavController(R.id.nav_host).addOnDestinationChangedListener(sentryNavListener)
    syncListenerManager.registerSyncListener(this, lifecycle)
  }

  override fun onPause() {
    super.onPause()
    findNavController(R.id.nav_host).removeOnDestinationChangedListener(sentryNavListener)
  }

  override fun onQuestionnaireLaunched(questionnaireConfig: QuestionnaireConfig) {
    // Data filter QRs are not persisted; reset filters when questionnaire is launched
    if (!questionnaireConfig.saveQuestionnaireResponse) {
      appMainViewModel.resetRegisterFilters.value = true
    }
  }

  override suspend fun onSubmitQuestionnaire(activityResult: ActivityResult) {
    if (activityResult.resultCode == RESULT_OK) {
      val questionnaireResponse: QuestionnaireResponse? =
        activityResult.data?.serializable(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE)
          as QuestionnaireResponse?
      val extractedResourceIds =
        activityResult.data?.serializable(
          QuestionnaireActivity.QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS,
        ) as List<IdType>? ?: emptyList()
      val questionnaireConfig =
        activityResult.data?.parcelable(QuestionnaireActivity.QUESTIONNAIRE_CONFIG)
          as QuestionnaireConfig?

      if (questionnaireConfig != null && questionnaireResponse != null) {
        eventBus.triggerEvent(
          AppEvent.OnSubmitQuestionnaire(
            QuestionnaireSubmission(
              questionnaireConfig = questionnaireConfig,
              questionnaireResponse = questionnaireResponse,
              extractedResourceIds = extractedResourceIds,
            ),
          ),
        )
      } else {
        Timber.e("QuestionnaireConfig & QuestionnaireResponse are both null")
      }
    }
  }

  private fun setupLocationServices() {
    if (
      appMainViewModel.applicationConfiguration.logGpsLocation.contains(
        LocationLogOptions.CALCULATE_DISTANCE_RULE_EXECUTOR,
      )
    ) {
      fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

      if (!LocationUtils.isLocationEnabled(this)) {
        showLocationSettingsDialog(
          Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            putExtra(ON_RESULT_TYPE, ActivityOnResultType.LOCATION.name)
          },
        )
      }

      if (!PermissionUtils.hasLocationPermissions(this)) {
        locationPermissionLauncher.launch(
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
          ),
        )
      }

      if (LocationUtils.isLocationEnabled(this) && PermissionUtils.hasLocationPermissions(this)) {
        fetchLocation()
      }
    }
  }

  private fun showLocationSettingsDialog(intent: Intent) {
    AlertDialog.Builder(this)
      .setMessage(getString(R.string.location_services_disabled))
      .setCancelable(true)
      .setPositiveButton(getString(R.string.yes)) { _, _ -> startForResult.launch(intent) }
      .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.cancel() }
      .show()
  }

  private fun fetchLocation() {
    val context = this
    lifecycleScope.launch {
      val retrievedLocation =
        async(dispatcherProvider.io()) {
            when {
              PermissionUtils.hasFineLocationPermissions(context) ->
                LocationUtils.getAccurateLocation(fusedLocationClient)
              PermissionUtils.hasCoarseLocationPermissions(context) ->
                LocationUtils.getApproximateLocation(fusedLocationClient)
              else -> null
            }
          }
          .await()
          ?.also {
            protoDataStore.writeLocationCoordinates(
              LocationCoordinate(
                latitude = it.latitude,
                longitude = it.longitude,
                altitude = it.altitude,
                timeStamp = Instant.now(),
              ),
            )
          }

      if (retrievedLocation == null) {
        withContext(dispatcherProvider.main()) {
          showToast(getString(R.string.failed_to_get_gps_location), Toast.LENGTH_LONG)
        }
      }
    }
  }

  override fun onSync(syncState: SyncState) {
    when (val syncJobStatus = syncState.currentSyncJobStatus) {
      is CurrentSyncJobStatus.Succeeded ->
        appMainViewModel.run {
          onEvent(
            AppMainEvent.UpdateSyncState(
              syncCounter = syncState.counter,
              currentSyncJobStatus = syncState.currentSyncJobStatus,
              lastSyncTime = formatLastSyncTimestamp(syncJobStatus.timestamp),
            ),
          )
          appMainViewModel.updateAppDrawerUIState(
            syncCounter = syncState.counter,
            currentSyncJobStatus = syncJobStatus,
          )
        }
      is CurrentSyncJobStatus.Failed ->
        appMainViewModel.run {
          onEvent(
            AppMainEvent.UpdateSyncState(
              syncCounter = syncState.counter,
              currentSyncJobStatus = syncState.currentSyncJobStatus,
              lastSyncTime = formatLastSyncTimestamp(syncJobStatus.timestamp),
            ),
          )
          updateAppDrawerUIState(
            syncCounter = syncState.counter,
            currentSyncJobStatus = syncJobStatus,
          )
        }
      else -> {
        // Do Nothing
      }
    }
  }

  private fun showSessionExpiredDialog() {
    AlertDialogue.showAlert(
      context = this,
      alertIntent = AlertIntent.CONFIRM,
      message = getString(org.smartregister.fhircore.engine.R.string.session_expired_form_saved),
      title = getString(org.smartregister.fhircore.engine.R.string.session_expired),
      confirmButton =
        AlertDialogButton(
          listener = { dialog ->
            dialog.dismiss()
            launchActivityWithNoBackStackHistory<LoginActivity>()
          },
          text = org.smartregister.fhircore.engine.R.string.questionnaire_alert_ack_button_title,
        ),
      cancellable = false,
    )
  }

  private fun overrideOnBackPressListener() {
    onBackPressedDispatcher.addCallback(
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val navHostFragment =
            (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment)
          if (navHostFragment.childFragmentManager.backStackEntryCount == 0) {
            AlertDialogue.showAlert(
              this@AppMainActivity,
              alertIntent = AlertIntent.CONFIRM,
              title = getString(R.string.exit_app),
              message = getString(R.string.exit_app_message),
              cancellable = false,
              confirmButton =
                AlertDialogButton(
                  listener = { finish() },
                ),
              neutralButton =
                AlertDialogButton(
                  listener = { dialog -> dialog.dismiss() },
                ),
            )
          } else {
            navHostFragment.navController.navigateUp()
          }
        }
      },
    )
  }
}

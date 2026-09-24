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

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.compose.material.ExperimentalMaterialApi
import androidx.lifecycle.MutableLiveData
import androidx.navigation.fragment.NavHostFragment
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.io.Serializable
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.sync.SyncState
import org.smartregister.fhircore.engine.task.FhirCarePlanGenerator
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.quest.app.fakes.Faker
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.robolectric.ActivityRobolectricTest
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity

@OptIn(ExperimentalMaterialApi::class)
@HiltAndroidTest
class AppMainActivityTest : ActivityRobolectricTest() {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @BindValue
  val configurationRegistry: ConfigurationRegistry = Faker.buildTestConfigurationRegistry()

  @BindValue
  val fhirCarePlanGenerator: FhirCarePlanGenerator = mockk(relaxed = true, relaxUnitFun = true)

  private lateinit var appMainActivity: AppMainActivity
  private var eventBus: EventBus = mockk(relaxUnitFun = true, relaxed = true)

  @Before
  fun setUp() {
    hiltRule.inject()
    appMainActivity = spyk(Robolectric.buildActivity(AppMainActivity::class.java).create().get())
    every { appMainActivity.eventBus } returns eventBus
    // The session-expired redirect runs on resume/sync; keep it inert for these tests
    mockkStatic(Context::isDeviceOnline)
    every { appMainActivity.isDeviceOnline() } returns false
  }

  @After
  fun unmockStatics() {
    unmockkStatic(Context::isDeviceOnline)
  }

  @Test
  fun testActivityIsStartedCorrectly() {
    Assert.assertNotNull(appMainActivity)
    val fragments = appMainActivity.supportFragmentManager.fragments
    Assert.assertEquals(1, fragments.size)
    Assert.assertTrue(fragments.first() is NavHostFragment)
  }

  override fun getActivity(): Activity {
    return appMainActivity
  }

  @Test
  fun testOnSyncWithSyncStateInProgress() {
    val viewModel = appMainActivity.appMainViewModel
    val initialSyncTime = viewModel.appMainUiState.value.lastSyncTime

    appMainActivity.onSync(
      SyncState(
        currentSyncJobStatus =
          CurrentSyncJobStatus.Running(SyncJobStatus.InProgress(SyncOperation.DOWNLOAD)),
        counter = 1,
      ),
    )

    // Timestamp will only updated for Finished.
    Assert.assertEquals(initialSyncTime, viewModel.appMainUiState.value.lastSyncTime)
  }

  @Test
  fun testOnSyncWithSyncStateFailedDoesNotUpdateTimestamp() {
    val viewModel = appMainActivity.appMainViewModel
    viewModel.sharedPreferencesHelper.write(
      SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name,
      "2022-05-19",
    )
    val initialTimestamp = viewModel.appMainUiState.value.lastSyncTime
    val syncJobStatus = CurrentSyncJobStatus.Failed(OffsetDateTime.now())
    appMainActivity.onSync(SyncState(currentSyncJobStatus = syncJobStatus, counter = 1))

    // Timestamp not update if status is Failed. Initial timestamp remains the same
    Assert.assertEquals(initialTimestamp, viewModel.appMainUiState.value.lastSyncTime)
  }

  @Test
  fun testOnSyncWithSyncStateFailedWhenTimestampIsNotNull() {
    val viewModel = appMainActivity.appMainViewModel
    appMainActivity.onSync(
      syncState =
        SyncState(
          counter = 1,
          currentSyncJobStatus = CurrentSyncJobStatus.Failed(OffsetDateTime.now()),
        ),
    )
    Assert.assertNotNull(viewModel.appMainUiState.value.lastSyncTime)
  }

  @Test
  fun testOnSyncWithSyncStateSucceeded() {
    // Arrange
    val viewModel = appMainActivity.appMainViewModel
    val stateSucceeded = CurrentSyncJobStatus.Succeeded(OffsetDateTime.now())
    appMainActivity.onSync(
      syncState = SyncState(counter = 1, currentSyncJobStatus = stateSucceeded),
    )

    Assert.assertEquals(
      viewModel.formatLastSyncTimestamp(timestamp = stateSucceeded.timestamp) + " (0s)",
      viewModel.getSyncTime(),
    )
  }

  @Test
  fun testOnSubmitQuestionnaireShouldUpdateLiveData() = runTest {
    appMainActivity.onSubmitQuestionnaire(
      ActivityResult(
        -1,
        Intent().apply {
          putExtra(
            QuestionnaireActivity.QUESTIONNAIRE_RESPONSE,
            QuestionnaireResponse().apply {
              status = QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS
            },
          )
          putExtra(
            QuestionnaireActivity.QUESTIONNAIRE_CONFIG,
            QuestionnaireConfig(taskId = "Task/12345", id = "questionnaireId") as Serializable,
          )
        },
      ),
    )

    val onSubmitQuestionnaireSlot = slot<AppEvent.OnSubmitQuestionnaire>()
    coVerify { eventBus.triggerEvent(capture(onSubmitQuestionnaireSlot)) }
    Assert.assertNotNull(onSubmitQuestionnaireSlot)
    val questionnaireSubmission = onSubmitQuestionnaireSlot.captured.questionnaireSubmission
    Assert.assertEquals("Task/12345", questionnaireSubmission.questionnaireConfig.taskId)
    Assert.assertEquals("questionnaireId", questionnaireSubmission.questionnaireConfig.id)
    Assert.assertEquals(
      QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS,
      questionnaireSubmission.questionnaireResponse.status,
    )
  }

  @Test
  fun testOnSubmitQuestionnaireShouldUpdateDataRefreshLivedata() = runTest {
    val appMainViewModel = mockk<AppMainViewModel>()
    val refreshLiveDataMock = mockk<MutableLiveData<Boolean?>>()
    every { refreshLiveDataMock.postValue(true) } just runs
    every { appMainActivity.appMainViewModel } returns appMainViewModel

    appMainActivity.onSubmitQuestionnaire(
      ActivityResult(
        -1,
        Intent().apply {
          putExtra(
            QuestionnaireActivity.QUESTIONNAIRE_RESPONSE,
            QuestionnaireResponse().apply {
              status = QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS
            },
          )
          putExtra(
            QuestionnaireActivity.QUESTIONNAIRE_CONFIG,
            QuestionnaireConfig(taskId = "Task/12345", id = "questionnaireId") as Serializable,
          )
        },
      ),
    )
    val onSubmitQuestionnaireSlot = slot<AppEvent.OnSubmitQuestionnaire>()
    coVerify { eventBus.triggerEvent(capture(onSubmitQuestionnaireSlot)) }
    Assert.assertNotNull(onSubmitQuestionnaireSlot)
  }
}

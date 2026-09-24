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

package org.smartregister.fhircore.quest.pdf

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.search.Search
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.mockk
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.ContentCache
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.yesterday
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.ui.pdf.PdfLauncherViewModel

@HiltAndroidTest
class PdfLauncherViewModelTest : RobolectricTest() {

  @get:Rule(order = 0) val hiltAndroidRule = HiltAndroidRule(this)

  @Inject lateinit var contentCache: ContentCache

  private lateinit var fhirEngine: FhirEngine
  private lateinit var defaultRepository: DefaultRepository
  private lateinit var configurationRegistry: ConfigurationRegistry
  private lateinit var viewModel: PdfLauncherViewModel

  @Before
  fun setUp() {
    hiltAndroidRule.inject()
    fhirEngine = mockk()
    configurationRegistry = mockk()
    defaultRepository =
      DefaultRepository(
        fhirEngine = fhirEngine,
        dispatcherProvider = mockk(),
        sharedPreferencesHelper = mockk(),
        configurationRegistry = configurationRegistry,
        configService = mockk(),
        configRulesExecutor = mockk(),
        fhirPathDataExtractor = mockk(),
        parser = mockk(),
        context = mockk(),
        contentCache = contentCache,
      )
    viewModel = PdfLauncherViewModel(defaultRepository, configurationRegistry)
  }

  @Test
  fun testRetrieveQuestionnaireResponseReturnsLatestResponse() = runTest {
    val patient = Patient().apply { id = "p1" }
    val questionnaire = Questionnaire().apply { id = "q1" }
    val olderQuestionnaireResponse =
      QuestionnaireResponse().apply {
        id = "qr2"
        meta.lastUpdated = yesterday()
        subject = patient.asReference()
        setQuestionnaire(questionnaire.asReference().reference)
      }
    val latestQuestionnaireResponse =
      QuestionnaireResponse().apply {
        id = "qr1"
        meta.lastUpdated = Date()
        subject = patient.asReference()
        setQuestionnaire(questionnaire.asReference().reference)
      }
    val questionnaireResponses =
      listOf(olderQuestionnaireResponse, latestQuestionnaireResponse).map {
        SearchResult(it, null, null)
      }

    coEvery { fhirEngine.search<QuestionnaireResponse>(any<Search>()) } returns
      questionnaireResponses
    val result = viewModel.retrieveQuestionnaireResponse(questionnaire.id, "Patient/${patient.id}")

    assertEquals(latestQuestionnaireResponse.id, result!!.id)
  }
}

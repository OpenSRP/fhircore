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

package org.smartregister.fhircore.quest.ui.pdf

import androidx.lifecycle.ViewModel
import com.google.android.fhir.search.Search
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import org.hl7.fhir.r4.model.Binary
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.util.helper.LocalizationHelper

/**
 * ViewModel for managing PDF generation related operations.
 *
 * This ViewModel provides methods for retrieving [QuestionnaireResponse] and [Binary] resources
 * required for generating PDFs.
 *
 * @param defaultRepository The repository for accessing local data.
 * @param configurationRegistry The registry for configuration and localization support.
 */
@HiltViewModel
class PdfLauncherViewModel
@Inject
constructor(
  val defaultRepository: DefaultRepository,
  val configurationRegistry: ConfigurationRegistry,
) : ViewModel() {

  /**
   * Retrieve the [QuestionnaireResponse] for the given questionnaire and subject.
   *
   * @param questionnaireId The ID of the questionnaire.
   * @param subjectReference The reference of the subject e.g. Patient/123.
   * @return The [QuestionnaireResponse] if found, otherwise null.
   */
  suspend fun retrieveQuestionnaireResponse(
    questionnaireId: String,
    subjectReference: String,
  ): QuestionnaireResponse? {
    val searchQuery = createQuestionnaireResponseSearchQuery(questionnaireId, subjectReference)
    return defaultRepository.search<QuestionnaireResponse>(searchQuery).maxByOrNull {
      it.meta.lastUpdated
    }
  }

  /**
   * Create a search query for [QuestionnaireResponse].
   *
   * @param questionnaireId The ID of the questionnaire.
   * @param subjectReference The reference of the subject e.g. Patient/123.
   * @return The search query for [QuestionnaireResponse].
   */
  private fun createQuestionnaireResponseSearchQuery(
    questionnaireId: String,
    subjectReference: String,
  ): Search {
    return Search(ResourceType.QuestionnaireResponse).apply {
      filter(QuestionnaireResponse.SUBJECT, { value = subjectReference })
      filter(
        QuestionnaireResponse.QUESTIONNAIRE,
        { value = "${ResourceType.Questionnaire}/$questionnaireId" },
      )
    }
  }

  /**
   * Retrieve the [Binary] resource for the given binary ID.
   *
   * @param binaryId The ID of the binary resource.
   * @return The [Binary] resource if found, otherwise null.
   */
  suspend fun retrieveBinary(binaryId: String): Binary? {
    return defaultRepository.loadResource<Binary>(binaryId)
  }

  /**
   * Provides a translation lambda for HtmlPopulator.
   *
   * @return A function that takes a translation key and returns the translated string.
   */
  fun getTranslationProvider(): (String) -> String = { translationKey ->
    configurationRegistry.localizationHelper.parseTemplate(
      LocalizationHelper.STRINGS_BASE_BUNDLE_NAME,
      Locale.getDefault(),
      "{{$translationKey}}",
    )
  }
}

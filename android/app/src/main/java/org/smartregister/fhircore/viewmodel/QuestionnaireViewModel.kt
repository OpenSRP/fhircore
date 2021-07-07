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

package org.smartregister.fhircore.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StructureMap
import org.smartregister.fhircore.FhirApplication
import org.smartregister.fhircore.activity.QuestionnaireActivity
import org.smartregister.fhircore.fragment.PatientDetailFragment

class QuestionnaireViewModel(application: Application, private val state: SavedStateHandle) :
  AndroidViewModel(application) {
  var questionnaireJson: String? = null
  val questionnaire: String
    get() {
      if (questionnaireJson == null) {
        questionnaireJson =
          getApplication<Application>()
            .assets
            .open(state[QuestionnaireActivity.QUESTIONNAIRE_FILE_PATH_KEY]!!)
            .bufferedReader()
            .use { it.readText() }
      }
      return questionnaireJson!!
    }

  fun saveResource(resource: Resource) {
    viewModelScope.launch { FhirApplication.fhirEngine(getApplication()).save(resource) }
  }

  fun saveBundleResources(bundle: Bundle, resourceId: String? = null) {
    if (!bundle.isEmpty) {
      bundle.entry.forEach { bundleEntry ->
        if (resourceId != null && bundleEntry.hasResource()) {
          bundleEntry.resource.id = resourceId
        }

        saveResource(bundleEntry.resource)
      }
    }
  }

  fun fetchStructureMap(context: Context, structureMapUrl: String?): StructureMap? {
    var structureMap: StructureMap? = null
    runBlocking {
      launch {
        val structureMapId = structureMapUrl?.substringAfterLast("/")
        if (structureMapId != null) {
          structureMap =
            FhirApplication.fhirEngine(context).load(StructureMap::class.java, structureMapId)
        }
      }
    }

    return structureMap
  }

  fun saveExtractedResources(
    context: Context,
    intent: Intent,
    questionnaireString: String,
    questionnaireResponse: QuestionnaireResponse
  ) {
    val iParser: IParser = FhirContext.forR4().newJsonParser()
    val questionnaire =
      iParser.parseResource(Questionnaire::class.java, questionnaireString) as Questionnaire

    val structureMapProvider = { structureMapUrl: String ->
      fetchStructureMap(context, structureMapUrl)
    }
    val bundle =
      ResourceMapper.extract(questionnaire, questionnaireResponse, structureMapProvider, context)

    val resourceId =
      if (intent.hasExtra(PatientDetailFragment.ARG_ITEM_ID))
        intent.getStringExtra(PatientDetailFragment.ARG_ITEM_ID)
      else null

    saveBundleResources(bundle, resourceId)
  }
}

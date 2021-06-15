/*
 * Copyright 2021 Ona Systems Inc
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

package org.smartregister.fhircore.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.commit
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import java.util.UUID
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.FhirApplication
import org.smartregister.fhircore.R
import org.smartregister.fhircore.fragment.PatientDetailFragment
import org.smartregister.fhircore.viewmodel.QuestionnaireViewModel

class QuestionnaireActivity : AppCompatActivity() {
  private val viewModel: QuestionnaireViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_questionnaire)

    supportActionBar!!.hide()

    // Only add the fragment once, when the activity is first created.
    if (savedInstanceState == null) {
      val fragment = QuestionnaireFragment()
      fragment.arguments =
        bundleOf(QuestionnaireFragment.BUNDLE_KEY_QUESTIONNAIRE to getQuestionnaire())

      supportFragmentManager.commit { add(R.id.container, fragment, QUESTIONNAIRE_FRAGMENT_TAG) }
    }

    findViewById<Button>(R.id.btn_save_client_info).setOnClickListener {
      val questionnaireFragment =
        supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) as
          QuestionnaireFragment
      savePatientResource(questionnaireFragment.getQuestionnaireResponse())
    }
  }

  fun savePatientResource(questionnaireResponse: QuestionnaireResponse) {

    val iParser: IParser = FhirContext.forR4().newJsonParser()
    val questionnaire =
      iParser.parseResource(
        org.hl7.fhir.r4.model.Questionnaire::class.java,
        viewModel.questionnaire
      ) as
        Questionnaire

    val patient = ResourceMapper.extract(questionnaire, questionnaireResponse) as Patient

    patient.id = patient.id ?: UUID.randomUUID().toString().toLowerCase()
    // FhirApplication.fhirEngine(applicationContext).save(patient)
    viewModel.saveResource(patient)

    this.startActivity(Intent(this, PatientListActivity::class.java))
  }

  private fun getQuestionnaire(): String {
    val questionnaire =
      FhirContext.forR4().newJsonParser().parseResource(viewModel.questionnaire) as Questionnaire

    intent.getStringExtra(PatientDetailFragment.ARG_ITEM_ID)?.let {
      var patient: Patient? = null
      viewModel.viewModelScope.launch {
        patient = FhirApplication.fhirEngine(applicationContext).load(Patient::class.java, it)
      }

      patient?.let {

        // set first name
        questionnaire.find("PR-name-text")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(it.name[0].given[0].value))
            )
        }

        // set family name
        questionnaire.find("PR-name-family")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(it.name[0].family))
            )
        }

        // set birthdate
        questionnaire.find("patient-0-birth-date")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent().setValue(DateType(it.birthDate))
            )
        }

        // set gender
        questionnaire.find("patient-0-gender")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(it.gender.toCode()))
            )
        }

        // set telecom
        questionnaire.find("PR-telecom-value")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(it.telecom[0].value))
            )
        }

        // set city
        questionnaire.find("PR-address-city")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(it.address[0].city))
            )
        }

        // set country
        questionnaire.find("PR-address-country")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(it.address[0].country))
            )
        }

        // set is-active
        questionnaire.find("PR-active")?.apply {
          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent().setValue(BooleanType(it.active))
            )
        }
      }
    }

    return FhirContext.forR4().newJsonParser().encodeResourceToString(questionnaire)
  }

  private fun Questionnaire.find(linkId: String): Questionnaire.QuestionnaireItemComponent? {
    return item.find(linkId, null)
  }

  private fun List<Questionnaire.QuestionnaireItemComponent>.find(
    linkId: String,
    default: Questionnaire.QuestionnaireItemComponent?
  ): Questionnaire.QuestionnaireItemComponent? {
    var result = default
    run loop@{
      forEach {
        if (it.linkId == linkId) {
          result = it
          return@loop
        } else if (it.item.isNotEmpty()) {
          result = it.item.find(linkId, result)
        }
      }
    }

    return result
  }

  companion object {
    const val QUESTIONNAIRE_TITLE_KEY = "questionnaire-title-key"
    const val QUESTIONNAIRE_FILE_PATH_KEY = "questionnaire-file-path-key"
    const val QUESTIONNAIRE_FRAGMENT_TAG = "questionnaire-fragment-tag"
  }
}

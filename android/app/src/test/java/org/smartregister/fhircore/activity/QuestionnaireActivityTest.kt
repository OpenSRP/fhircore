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

import android.app.Activity
import android.content.Intent
import com.google.android.fhir.datacapture.QuestionnaireFragment
import java.text.SimpleDateFormat
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Address
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Patient
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.smartregister.fhircore.FhirApplication
import org.smartregister.fhircore.fragment.PatientDetailFragment
import org.smartregister.fhircore.shadow.FhirApplicationShadow

@Config(shadows = [FhirApplicationShadow::class])
class QuestionnaireActivityTest : ActivityRobolectricTest() {

  private lateinit var questionnaireActivity: QuestionnaireActivity

  @Before
  fun setUp() {

    init()

    val intent =
      Intent().apply {
        putExtra(QuestionnaireActivity.QUESTIONNAIRE_TITLE_KEY, "Patient registration")
        putExtra(QuestionnaireActivity.QUESTIONNAIRE_FILE_PATH_KEY, "patient-registration.json")
        putExtra(PatientDetailFragment.ARG_ITEM_ID, TEST_PATIENT_1_ID)
      }
    questionnaireActivity =
      Robolectric.buildActivity(QuestionnaireActivity::class.java, intent).create().resume().get()
  }

  @Test
  fun testActivityShouldNotNull() {
    Assert.assertNotNull(questionnaireActivity)
  }

  @Test
  fun testVerifyPrePopulatedQuestionnaire() {
    val fragment =
      questionnaireActivity.supportFragmentManager.findFragmentByTag(
        QuestionnaireActivity.QUESTIONNAIRE_FRAGMENT_TAG
      ) as
        QuestionnaireFragment

    Assert.assertNotNull(fragment)

    val response = fragment.getQuestionnaireResponse()
    // TODO change these to proper mapping
    Assert.assertEquals(
      TEST_PATIENT_1.name[0].given[0].toString(),
      response.item[1].item[0].item[0].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.name[0].family,
      response.item[1].item[0].item[1].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.birthDate.toString(),
      response.item[1].item[1].answer[0].valueDateType.value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.gender.toCode(),
      response.item[1].item[2].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.telecom[0].value,
      response.item[1].item[3].item[1].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.address[0].city,
      response.item[1].item[4].item[0].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.address[0].country,
      response.item[1].item[4].item[1].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.active,
      response.item[1].item[5].answer[0].valueBooleanType.booleanValue()
    )
  }

  override fun getActivity(): Activity {
    return questionnaireActivity
  }

  private fun init() {
    runBlocking { FhirApplication.fhirEngine(FhirApplication.getContext()).save(TEST_PATIENT_1) }
  }

  companion object {
    const val TEST_PATIENT_1_ID = "test_patient_1"
    var TEST_PATIENT_1 = Patient()

    init {
      TEST_PATIENT_1.id = TEST_PATIENT_1_ID
      TEST_PATIENT_1.gender = Enumerations.AdministrativeGender.MALE
      TEST_PATIENT_1.name =
        mutableListOf(
          HumanName().apply {
            addGiven("jane")
            setFamily("Mc")
          }
        )
      TEST_PATIENT_1.telecom = mutableListOf(ContactPoint().apply { value = "12345678" })
      TEST_PATIENT_1.address =
        mutableListOf(
          Address().apply {
            city = "Nairobi"
            country = "Kenya"
          }
        )
      TEST_PATIENT_1.active = true
      TEST_PATIENT_1.birthDate = SimpleDateFormat("yyyy-MM-dd").parse("2021-05-25")
    }
  }
}

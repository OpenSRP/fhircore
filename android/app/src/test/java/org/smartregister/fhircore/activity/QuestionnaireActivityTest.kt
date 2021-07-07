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

package org.smartregister.fhircore.activity

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.widget.Button
import com.google.android.fhir.datacapture.QuestionnaireFragment
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import java.text.SimpleDateFormat
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Address
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Patient
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.smartregister.fhircore.FhirApplication
import org.smartregister.fhircore.R
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
    val controller = Robolectric.buildActivity(QuestionnaireActivity::class.java, intent)
    questionnaireActivity = spyk(controller.create().resume().get())

    /*questionnaireActivity = spyk(objToCopy = ReflectionHelpers.getField(controller, "component"))
    ReflectionHelpers.setField(controller, "component", questionnaireActivity)
    val questionnaireActivity_ = spyk(objToCopy = ReflectionHelpers.getField(controller, "_component_"))
    ReflectionHelpers.setField(questionnaireActivity_, "__target__", questionnaireActivity)
    ReflectionHelpers.setField(controller, "_component_", questionnaireActivity_)*/

    /*questionnaireActivity = spyk(objToCopy = ReflectionHelpers.getField(controller, "component"))
    ReflectionHelpers.setField(controller, "component", questionnaireActivity)

    val delegate: AppCompatDelegate =
      AppCompatDelegate.create(
        ApplicationProvider.getApplicationContext(),
        questionnaireActivity,
        questionnaireActivity
      )
    every { questionnaireActivity.delegate } returns delegate

    val questionnaireActivity_ = spyk(objToCopy = ReflectionHelpers.getField(controller, "_component_"))
    ReflectionHelpers.setField(controller, "_component_", questionnaireActivity_)

    controller.create().resume().get() */
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

    shadowOf(Looper.getMainLooper()).idle()

    val response = fragment.getQuestionnaireResponse()
    Assert.assertEquals(
      TEST_PATIENT_1.name[0].given[0].toString(),
      response.item[0].item[0].item[0].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.name[0].family,
      response.item[0].item[0].item[1].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.birthDate.toString(),
      response.item[0].item[1].answer[0].valueDateType.value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.gender.toCode(),
      response.item[0].item[2].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.telecom[0].value,
      response.item[0].item[3].item[1].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.address[0].city,
      response.item[0].item[4].item[0].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.address[0].country,
      response.item[0].item[4].item[1].answer[0].value.toString()
    )
    Assert.assertEquals(
      TEST_PATIENT_1.active,
      response.item[0].item[5].answer[0].valueBooleanType.booleanValue()
    )
  }

  @Ignore
  @Test
  fun `save-button click should call savedExtractedResources()`() {
    every { questionnaireActivity.saveExtractedResources(any()) } just runs

    questionnaireActivity.findViewById<Button>(R.id.btn_save_client_info).performClick()

    verify(exactly = 1) { questionnaireActivity.findViewById<Button>(any()) }
    verify(exactly = 1) { questionnaireActivity.finish() }
    verify(exactly = 1) { questionnaireActivity.saveExtractedResources(any()) }
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

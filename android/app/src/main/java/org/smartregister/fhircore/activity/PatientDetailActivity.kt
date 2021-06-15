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
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import org.smartregister.fhircore.R
import org.smartregister.fhircore.fragment.PatientDetailFragment

/** An activity representing a single Patient detail screen. */
class PatientDetailActivity : AppCompatActivity() {
  lateinit var fragment: PatientDetailFragment

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_patient_detail)
    setSupportActionBar(findViewById(R.id.detail_toolbar))

    // Show the Up button in the action bar.
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    if (savedInstanceState == null) {
      // Create the detail fragment and add it to the activity
      // using a fragment transaction.
      fragment =
        PatientDetailFragment().apply {
          arguments =
            Bundle().apply {
              putString(
                PatientDetailFragment.ARG_ITEM_ID,
                intent.getStringExtra(PatientDetailFragment.ARG_ITEM_ID)
              )
            }
        }

      supportFragmentManager
        .beginTransaction()
        .add(R.id.patient_detail_container, fragment)
        .commit()
    }

    findViewById<Button>(R.id.btn_record_vaccine).setOnClickListener {
      startActivity(
        Intent(this, RecordVaccineActivity::class.java).apply {
          putExtra(QuestionnaireActivity.QUESTIONNAIRE_TITLE_KEY, "Record Vaccine")
          putExtra(QuestionnaireActivity.QUESTIONNAIRE_FILE_PATH_KEY, "record-vaccine.json")

          putExtra(PATIENT_ID, fragment.patitentId)
          putExtra(DOSE_NUMBER, fragment.doseNumber)
          putExtra(INITIAL_DOSE, fragment.initialDose)
        }
      )
    }
  }

  override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    menuInflater.inflate(R.menu.profile_menu, menu)
    return true
  }
  override fun onOptionsItemSelected(item: MenuItem) =
    when (item.itemId) {
      R.id.patient_profile_edit -> {
        editPatientDetails()

        true
      }
      else -> super.onOptionsItemSelected(item)
    }

  private fun editPatientDetails() {
    fragment.editPatient()
  }
}

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

package org.smartregister.fhircore.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.FhirEngine
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Immunization
import org.hl7.fhir.r4.model.PositiveIntType
import org.smartregister.fhircore.FhirApplication
import org.smartregister.fhircore.R
import org.smartregister.fhircore.activity.QuestionnaireActivity
import org.smartregister.fhircore.adapter.ObservationItemRecyclerViewAdapter
import org.smartregister.fhircore.util.Utils
import org.smartregister.fhircore.viewmodel.PatientListViewModel
import org.smartregister.fhircore.viewmodel.PatientListViewModelFactory

/**
 * A fragment representing a single Patient detail screen. This fragment is contained in a
 * [PatientDetailActivity].
 */
class PatientDetailFragment : Fragment() {

  var patitentId: String? = null
  lateinit var rootView: View
  lateinit var viewModel: PatientListViewModel

  val finalDoseNumber = 2
  var doseNumber = 0
  var initialDose: String = ""

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    rootView = inflater.inflate(R.layout.patient_detail, container, false)

    val adapter = ObservationItemRecyclerViewAdapter()

    // Commenting as we don't need this in Patient Detail Screen
    /*val recyclerView: RecyclerView = rootView.findViewById(R.id.observation_list)
    recyclerView.adapter = adapter*/

    val fhirEngine: FhirEngine = FhirApplication.fhirEngine(requireContext())

    viewModel =
      ViewModelProvider(
          this,
          PatientListViewModelFactory(this.requireActivity().application, fhirEngine)
        )
        .get(PatientListViewModel::class.java)

    viewModel.liveSearchPatient.observe(
      viewLifecycleOwner,
      Observer<PatientListViewModel.PatientItem> { setupPatientData(it) }
    )

    arguments?.let {
      if (it.containsKey(Companion.ARG_ITEM_ID)) {
        it.getString(Companion.ARG_ITEM_ID)?.let { it1 ->
          viewModel.getPatientItem(it1)
          patitentId = it1
        }
      }
    }
    viewModel.searchResults()

    return rootView
  }

  private fun updateVaccineStatus(immunizations: List<Immunization>) {

    var isFullyVaccinated = false
    viewModel.viewModelScope.launch {
      immunizations.forEach { immunization ->
        doseNumber = (immunization.protocolApplied[0].doseNumber as PositiveIntType).value
        initialDose = immunization.vaccineCode.coding.first().code
        if (isFullyVaccinated) {
          return@forEach
        }
        val nextDoseNumber = doseNumber + 1
        val vaccineDate = immunization.occurrenceDateTimeType.toHumanDisplay()
        val nextVaccineDate = Utils.addDays(vaccineDate, 28)
        val tvVaccineRecorded = rootView.findViewById<TextView>(R.id.vaccination_status)
        tvVaccineRecorded.text =
          resources.getString(
            R.string.immunization_brief_text,
            immunization.vaccineCode.text,
            doseNumber
          )

        val tvVaccineSecondDose = rootView.findViewById<TextView>(R.id.vaccination_second_dose)
        val btnRecordVaccine = activity?.findViewById<Button>(R.id.btn_record_vaccine)
        tvVaccineSecondDose.visibility = View.VISIBLE
        if (doseNumber == finalDoseNumber) {
          isFullyVaccinated = true
          tvVaccineRecorded.text = resources.getString(R.string.fully_vaccinated)
          tvVaccineSecondDose.text = resources.getString(R.string.view_vaccine_certificate)
          if (btnRecordVaccine != null) {
            btnRecordVaccine.visibility = View.GONE
          }
        } else {
          tvVaccineSecondDose.text =
            resources.getString(
              R.string.immunization_next_dose_text,
              nextDoseNumber,
              nextVaccineDate
            )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // load immunization data
    viewModel.searchImmunizations(patitentId)
  }

  private fun setupPatientData(patient: PatientListViewModel.PatientItem?) {
    val gender = if (patient?.gender == "male") 'M' else 'F'
    if (patient != null) {
      var patientDetailLabel =
        patient?.name +
          ", " +
          gender +
          ", " +
          patient?.dob?.let { it1 -> Utils.getAgeFromDate(it1) }
      activity?.findViewById<TextView>(R.id.patient_bio_data)?.text = patientDetailLabel
      activity?.findViewById<TextView>(R.id.id_patient_number)?.text = "ID: " + patient.logicalId
      patitentId = patient.logicalId
    }
  }

  fun editPatient() {

    viewModel.liveSearchPatient.value?.let {
      startActivity(
        Intent(requireContext(), QuestionnaireActivity::class.java).apply {
          putExtra(QuestionnaireActivity.QUESTIONNAIRE_TITLE_KEY, "Patient registration")
          putExtra(QuestionnaireActivity.QUESTIONNAIRE_FILE_PATH_KEY, "patient-registration.json")
          putExtra(Companion.ARG_ITEM_ID, it.logicalId)
        }
      )
    }
  }

  companion object {
    /** The fragment argument representing the patient item ID that this fragment represents. */
    const val ARG_ITEM_ID = "patient_item_id"
  }
}

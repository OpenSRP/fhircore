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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.FhirEngine
import org.smartregister.fhircore.FhirApplication
import org.smartregister.fhircore.R
import org.smartregister.fhircore.adapter.ImmunizationItemRecyclerViewAdapter
import org.smartregister.fhircore.util.SharedPrefrencesHelper
import org.smartregister.fhircore.util.Utils
import org.smartregister.fhircore.viewmodel.PatientListViewModel
import org.smartregister.fhircore.viewmodel.PatientListViewModelFactory

/**
 * A fragment representing a single Patient detail screen. This fragment is contained in a
 * [PatientDetailActivity].
 */
class PatientDetailFragment : Fragment() {

  var patitentId: String? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    val rootView = inflater.inflate(R.layout.patient_detail, container, false)

    val adapter = ImmunizationItemRecyclerViewAdapter()

    val recyclerView: RecyclerView = rootView.findViewById(R.id.observation_list)
    recyclerView.adapter = adapter

    val fhirEngine: FhirEngine = FhirApplication.fhirEngine(requireContext())

    val viewModel: PatientListViewModel =
      ViewModelProvider(
          this,
          PatientListViewModelFactory(this.requireActivity().application, fhirEngine)
        )
        .get(PatientListViewModel::class.java)

    viewModel.liveSearchPatient.observe(viewLifecycleOwner, { setupPatientData(it) })

    arguments?.let {
      if (it.containsKey(ARG_ITEM_ID)) {
        it.getString(ARG_ITEM_ID)?.let { it1 ->
          viewModel.getPatientItem(it1)
          patitentId = it1
        }
      }
    }
    viewModel.searchResults()

    patitentId?.let {
      val vaccineRecorded = SharedPrefrencesHelper.read(it, "")
      vaccineRecorded?.let { it1 ->
        if (it1.isNotEmpty()) {
          val tvVaccineRecorded = rootView.findViewById<TextView>(R.id.observation_detail)
          tvVaccineRecorded?.text = "Received $it1 dose 1"
        }
      }
    }

    // load immunization data
    viewModel.searchImmunizations(patitentId)

    viewModel.liveSearchImmunization.observe(
      viewLifecycleOwner,
      {
        adapter.submitList(it)
        if (it.isNotEmpty()) {
          val tvNoVaccinePlaceholder =
            rootView.findViewById<TextView>(R.id.no_vaccination_placeholder)
          tvNoVaccinePlaceholder.visibility = View.GONE
          val noVaccinePlaceholder =
            rootView.findViewById<View>(R.id.view_vaccination_status_separator)
          noVaccinePlaceholder.visibility = View.GONE
        }
      }
    )

    return rootView
  }

  private fun setupPatientData(patient: PatientListViewModel.PatientItem?) {
    val gender = if (patient?.gender == "male") 'M' else 'F'
    if (patient != null) {
      val patientDetailLabel =
        patient.name + ", " + gender + ", " + patient.dob.let { it1 -> Utils.getAgeFromDate(it1) }
      activity?.findViewById<TextView>(R.id.patient_bio_data)?.text = patientDetailLabel
      activity?.findViewById<TextView>(R.id.id_patient_number)?.text = "ID: " + patient.logicalId
      patitentId = patient.logicalId
    }
  }

  companion object {
    /** The fragment argument representing the patient item ID that this fragment represents. */
    const val ARG_ITEM_ID = "patient_item_id"
  }
}

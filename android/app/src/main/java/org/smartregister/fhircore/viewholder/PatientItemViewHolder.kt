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

package org.smartregister.fhircore.viewholder

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import org.smartregister.fhircore.R
import org.smartregister.fhircore.fragment.PatientListFragment
import org.smartregister.fhircore.util.Utils
import org.smartregister.fhircore.util.Utils.getAgeFromDate
import org.smartregister.fhircore.viewmodel.PatientListViewModel

class PatientItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
  private val tvPatientDemographics: TextView = itemView.findViewById(R.id.tv_patient_demographics)
  private val tvDateLastSeen: TextView = itemView.findViewById(R.id.date_last_seen)
  private val vaccineActionContainer: ViewGroup =
    itemView.findViewById(R.id.vaccine_action_container)
  fun bindTo(
    patientItem: PatientListViewModel.PatientItem,
    onItemClicked: (PatientListFragment.Intention, PatientListViewModel.PatientItem) -> Unit,
    patientStatusObserver: (String, Observer<PatientListViewModel.PatientStatus>) -> Unit
  ) {
    setPatientStatus(null, patientItem, this.vaccineActionContainer, onItemClicked)
    this.tvPatientDemographics.text = getPatientDemographics(patientItem)
    this.tvPatientDemographics.setOnClickListener {
      onItemClicked(PatientListFragment.Intention.VIEW, patientItem)
    }

    patientStatusObserver(patientItem.logicalId) {
      setPatientStatus(it, patientItem, this.vaccineActionContainer, onItemClicked)
    }
  }

  private fun setPatientStatus(
    patientStatus: PatientListViewModel.PatientStatus?,
    patientItem: PatientListViewModel.PatientItem,
    vaccineActionContainer: ViewGroup,
    onItemClicked: (PatientListFragment.Intention, PatientListViewModel.PatientItem) -> Unit,
  ) {
    val status = patientStatus?.status ?: return

    tvDateLastSeen.text =
      vaccineActionContainer.context.getString(R.string.client_last_seen, patientStatus.details)

    Utils.hideViewsByTag(vaccineActionContainer, "status_container")

    when (status) {
      PatientListViewModel.VaccineStatus.VACCINATED -> {
        Utils.showViewById(vaccineActionContainer, R.id.status_fully_vaccinated_container)

        vaccineActionContainer.setOnClickListener {}
      }
      PatientListViewModel.VaccineStatus.OVERDUE -> {
        Utils.showViewById(vaccineActionContainer, R.id.status_overdue_container)

        vaccineActionContainer.setOnClickListener {
          onItemClicked(PatientListFragment.Intention.RECORD_VACCINE, patientItem)
        }
      }
      PatientListViewModel.VaccineStatus.PARTIAL -> {
        Utils.showViewById(vaccineActionContainer, R.id.status_vaccinated_container)

        vaccineActionContainer.findViewById<TextView>(R.id.tv_vaccinated_previous_dose).text =
          vaccineActionContainer.context.getString(
            R.string.status_received_vaccine,
            1,
            patientStatus.details
          )

        vaccineActionContainer.setOnClickListener {
          onItemClicked(PatientListFragment.Intention.RECORD_VACCINE, patientItem)
        }
      }
      PatientListViewModel.VaccineStatus.DUE -> {
        Utils.showViewById(vaccineActionContainer, R.id.status_due_container)

        vaccineActionContainer.setOnClickListener {
          onItemClicked(PatientListFragment.Intention.RECORD_VACCINE, patientItem)
        }

        tvDateLastSeen.text = ""
      }
    }
  }

  private fun getPatientDemographics(patientItem: PatientListViewModel.PatientItem): String {
    val age = getAgeFromDate(patientItem.dob)
    val names = patientItem.name.split(' ')
    val gender = if (patientItem.gender == "male") 'M' else 'F'
    return listOf(names[1], names[0], gender, "$age").joinToString()
  }
}
